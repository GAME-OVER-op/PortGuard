package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SettingsFileRepository(
    private val rootShell: RootShell,
) {
    private companion object {
        // Settings screens use separate ViewModels/Repository instances. A process-wide
        // mutex makes every read-modify-write transaction atomic across all of them.
        val configMutationMutex = Mutex()
        val listMutationMutex = Mutex()
    }
    suspend fun readConfigRaw(): String? = rootShell.readText(PortGuardPaths.CONFIG_JSON)

    suspend fun writeConfigRaw(content: String): Boolean = configMutationMutex.withLock {
        writeConfigRawUnlocked(content)
    }

    private suspend fun writeConfigRawUnlocked(content: String): Boolean {
        return rootShell.writeTextAtomic(PortGuardPaths.CONFIG_JSON, content)
    }

    suspend fun readConfig(): PortGuardConfig? {
        val raw = readConfigRaw() ?: return null
        return runCatching { parseConfig(raw) }.getOrNull()
    }

    suspend fun ensureDefaultConfig(): Boolean = configMutationMutex.withLock {
        if (!ensureSettingsLayout()) return@withLock false
        if (rootShell.fileExists(PortGuardPaths.CONFIG_JSON)) return@withLock true
        writeConfigRawUnlocked(buildDefaultConfigJson().toString(2))
    }

    suspend fun updateDashboardConfig(
        activeProtection: ActiveProtectionMode,
        learningMode: Boolean,
        reactionMode: ReactionMode,
        protectLoopbackOnly: Boolean,
    ): Boolean = mutateConfig { json ->
        json.put("active_protection", activeProtection.raw)
        json.put("learning_mode", learningMode)
        json.put("reaction_mode", reactionMode.raw)
        json.put("protect_loopback_only", protectLoopbackOnly)

        // Reaction mode changes the real xtables counter cadence. Keep the stored
        // detector window honest so UI and Rust use the same effective value.
        val loopIntervalMs = json.optInt("loop_interval_ms", 3000).coerceAtLeast(500)
        val counterRefreshLoops = json.optInt("counter_refresh_loops", 5).coerceAtLeast(5)
        val minWindow = effectiveCounterRefreshSecs(loopIntervalMs, counterRefreshLoops, reactionMode)
        json.put("scan_window_secs", json.optInt("scan_window_secs", 60).coerceAtLeast(minWindow))
    }

    suspend fun updateAdvancedConfig(
        suspiciousUniquePorts: Int,
        suspiciousAttempts: Int,
        suspiciousRuleHits: Int,
        scanWindowSecs: Int,
        warnCooldownSecs: Int,
        reactionCooldownSecs: Int,
        loopIntervalMs: Int,
        reloadCheckSecs: Int,
        packageRefreshSecs: Int,
        counterRefreshLoops: Int,
        resolveProcessDetails: Boolean,
        protectLoopbackOnly: Boolean,
    ): Boolean = mutateConfig { json ->
        val normalizedLoopMs = loopIntervalMs.coerceAtLeast(500)
        val normalizedCounterLoops = counterRefreshLoops.coerceAtLeast(5)
        val reactionMode = ReactionMode.fromRaw(json.optString("reaction_mode"))
        val minWindow = effectiveCounterRefreshSecs(
            normalizedLoopMs,
            normalizedCounterLoops,
            reactionMode,
        )
        json.put("suspicious_unique_ports", suspiciousUniquePorts.coerceAtLeast(1))
        json.put("suspicious_attempts", suspiciousAttempts.coerceAtLeast(2))
        json.put("suspicious_rule_hits", suspiciousRuleHits.coerceAtLeast(2))
        json.put("scan_window_secs", scanWindowSecs.coerceAtLeast(minWindow))
        json.put("warn_cooldown_secs", warnCooldownSecs.coerceAtLeast(1))
        json.put("reaction_cooldown_secs", reactionCooldownSecs.coerceAtLeast(5))
        json.put("loop_interval_ms", normalizedLoopMs)
        json.put("reload_check_secs", reloadCheckSecs.coerceAtLeast(30))
        json.put("package_refresh_secs", packageRefreshSecs.coerceAtLeast(120))
        json.put("counter_refresh_loops", normalizedCounterLoops)
        json.put("resolve_process_details", resolveProcessDetails)
        json.put("protect_loopback_only", protectLoopbackOnly)
    }


    suspend fun updateTrafficAndRuntimeConfig(
        tcp4Enabled: Boolean,
        udp4Enabled: Boolean,
        tcp6Enabled: Boolean,
        udp6Enabled: Boolean,
        userAppsOnly: Boolean,
        networkRefreshSecs: Int,
        activeSelfTestEnabled: Boolean,
        selfTestTimeoutMs: Int,
    ): Boolean = mutateConfig { json ->
        json.put("tcp4_enabled", tcp4Enabled)
        json.put("udp4_enabled", udp4Enabled)
        json.put("tcp6_enabled", tcp6Enabled)
        json.put("udp6_enabled", udp6Enabled)
        json.put("user_apps_only", userAppsOnly)
        json.put("network_refresh_secs", networkRefreshSecs.coerceAtLeast(10))
        json.put("active_self_test_enabled", activeSelfTestEnabled)
        json.put("self_test_timeout_ms", selfTestTimeoutMs.coerceAtLeast(250))
    }


    suspend fun updateSystemConfig(
        chainName: String,
        stateDir: String,
        maxRules: Int,
        autoDiscoverPackages: Boolean,
        packageUidSources: List<String>,
        summaryPortLimit: Int,
        ignoredOwnerPackages: List<String>,
    ): Boolean = mutateConfig { json ->
        json.put("chain_name", chainName.ifBlank { "PORTGUARD" })
        json.put("state_dir", stateDir.ifBlank { PortGuardPaths.STATE_DIR })
        json.put("max_rules", maxRules.coerceAtLeast(1000))
        json.put("auto_discover_packages", autoDiscoverPackages)
        json.put("package_uid_sources", JSONArray(packageUidSources.filter { it.isNotBlank() }.distinct()))
        json.put("summary_port_limit", summaryPortLimit.coerceAtLeast(1))
        json.put("ignored_owner_packages", JSONArray(ignoredOwnerPackages.filter { it.isNotBlank() }.distinct()))
    }


    suspend fun readTrustedPackages(): List<String> = readList(PortGuardPaths.TRUSTED_PACKAGES)
    suspend fun readTrustedUids(): List<String> = readList(PortGuardPaths.TRUSTED_UIDS)
    suspend fun readKillExceptions(): List<String> = readList(PortGuardPaths.KILL_EXCEPTIONS)
    suspend fun readScanIgnorePackages(): List<String> = readList(PortGuardPaths.SCAN_IGNORE_PACKAGES)

    suspend fun readList(path: String): List<String> {
        val raw = rootShell.readText(path).orEmpty()
        return raw.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    suspend fun writeList(path: String, values: List<String>): Boolean = listMutationMutex.withLock {
        val normalized = values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = "\n")

        val payload = if (normalized.isBlank()) "" else "${normalized}\n"
        rootShell.writeTextAtomic(path = path, content = payload)
    }

    suspend fun ensureSettingsLayout(): Boolean {
        val created = rootShell.mkdirs(PortGuardPaths.SETTINGS_DIR)
        val stateCreated = rootShell.mkdirs(PortGuardPaths.STATE_DIR)
        return created && stateCreated
    }

    suspend fun applyPreset(preset: PortGuardPreset): SettingsActionResult {
        val saved = mutateConfig { json ->
            when (preset) {
            PortGuardPreset.BALANCED -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.OFF.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 3)
                json.put("suspicious_rule_hits", 3)
                json.put("scan_window_secs", 60)
                json.put("warn_cooldown_secs", 15)
                json.put("reaction_cooldown_secs", 60)
                json.put("loop_interval_ms", 3000)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 300)
                json.put("counter_refresh_loops", 5)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 30)
                json.put("firewall_reconcile_secs", 300)
                json.put("udp_stabilize_secs", 6)
                json.put("udp_ephemeral_stabilize_secs", 45)
                json.put("udp_grace_secs", 12)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
            PortGuardPreset.STRICT -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.OFF.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 2)
                json.put("suspicious_rule_hits", 2)
                json.put("scan_window_secs", 60)
                json.put("warn_cooldown_secs", 10)
                json.put("reaction_cooldown_secs", 45)
                json.put("loop_interval_ms", 2500)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 180)
                json.put("counter_refresh_loops", 5)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 20)
                json.put("firewall_reconcile_secs", 180)
                json.put("udp_stabilize_secs", 5)
                json.put("udp_ephemeral_stabilize_secs", 30)
                json.put("udp_grace_secs", 10)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
            PortGuardPreset.AGGRESSIVE -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.FORCE_STOP.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 2)
                json.put("suspicious_rule_hits", 2)
                json.put("scan_window_secs", 30)
                json.put("warn_cooldown_secs", 5)
                json.put("reaction_cooldown_secs", 30)
                json.put("loop_interval_ms", 2000)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 120)
                json.put("counter_refresh_loops", 5)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 10)
                json.put("firewall_reconcile_secs", 120)
                json.put("udp_stabilize_secs", 3)
                json.put("udp_ephemeral_stabilize_secs", 20)
                json.put("udp_grace_secs", 8)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
            }
        }
        return if (saved) {
            SettingsActionResult(
                true,
                when (preset) {
                    PortGuardPreset.BALANCED -> "Пресет «Сбалансированный» применён."
                    PortGuardPreset.STRICT -> "Пресет «Строгий» применён."
                    PortGuardPreset.AGGRESSIVE -> "Пресет «Агрессивный» применён."
                }
            )
        } else {
            SettingsActionResult(false, "Не удалось применить выбранный пресет.")
        }
    }

    suspend fun resetToRecommended(): SettingsActionResult {
        if (!ensureSettingsLayout()) return SettingsActionResult(false, "Не удалось подготовить папку settings.")
        val configOk = writeConfigRaw(buildDefaultConfigJson().toString(2))
        val trustedOk = writeList(PortGuardPaths.TRUSTED_PACKAGES, emptyList())
        val uidOk = writeList(PortGuardPaths.TRUSTED_UIDS, emptyList())
        val killOk = writeList(PortGuardPaths.KILL_EXCEPTIONS, emptyList())
        val scanOk = writeList(PortGuardPaths.SCAN_IGNORE_PACKAGES, emptyList())
        val success = configOk && trustedOk && uidOk && killOk && scanOk
        return SettingsActionResult(
            success = success,
            message = if (success) "Настройки сброшены к рекомендуемым значениям." else "Не удалось полностью сбросить настройки.",
        )
    }

    suspend fun exportSettingsBundle(): SettingsActionResult {
        if (!ensureSettingsLayout()) return SettingsActionResult(false, "Не удалось подготовить папку settings перед экспортом.")
        val configRaw = readConfigRaw().orEmpty().ifBlank { buildDefaultConfigJson().toString(2) }
        val bundle = SettingsBackupBundle(
            exportedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            configRaw = configRaw,
            trustedPackages = readTrustedPackages(),
            trustedUids = readTrustedUids(),
            killExceptions = readKillExceptions(),
            scanIgnorePackages = readScanIgnorePackages(),
        )
        val json = JSONObject().apply {
            put("version", 1)
            put("exported_at", bundle.exportedAt)
            put("config_raw", bundle.configRaw)
            put("trusted_packages", JSONArray(bundle.trustedPackages))
            put("trusted_uids", JSONArray(bundle.trustedUids))
            put("kill_exceptions", JSONArray(bundle.killExceptions))
            put("scan_ignore_packages", JSONArray(bundle.scanIgnorePackages))
        }
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val timestampPath = PortGuardPaths.SETTINGS_BACKUP_TIMESTAMP_PREFIX + timestamp + ".json"
        val dirOk = rootShell.mkdirs(PortGuardPaths.BACKUP_DIR)
        val latestOk = dirOk && rootShell.writeTextAtomic(PortGuardPaths.SETTINGS_BACKUP_LATEST_JSON, json.toString(2))
        val timestampOk = dirOk && rootShell.writeTextAtomic(timestampPath, json.toString(2))
        val success = latestOk && timestampOk
        return SettingsActionResult(
            success = success,
            message = if (success) "Резервная копия настроек сохранена в Download/PortGuard." else "Не удалось экспортировать настройки в резервную копию.",
            path = if (success) PortGuardPaths.SETTINGS_BACKUP_LATEST_JSON else null,
        )
    }

    suspend fun importSettingsBundle(): SettingsActionResult {
        val raw = rootShell.readText(PortGuardPaths.SETTINGS_BACKUP_LATEST_JSON)
            ?: return SettingsActionResult(false, "Файл резервной копии не найден: ${PortGuardPaths.SETTINGS_BACKUP_LATEST_JSON}")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return SettingsActionResult(false, "Файл резервной копии повреждён и не является валидным JSON.")

        val configRaw = json.optString("config_raw").ifBlank { buildDefaultConfigJson().toString(2) }
        if (runCatching { parseConfig(configRaw) }.isFailure) {
            return SettingsActionResult(false, "В резервной копии config_raw содержит повреждённый JSON. Текущие настройки не изменены.")
        }
        val trustedPackages = json.optJSONArray("trusted_packages").toStringList()
        val trustedUids = json.optJSONArray("trusted_uids").toStringList()
        val killExceptions = json.optJSONArray("kill_exceptions").toStringList()
        val scanIgnore = json.optJSONArray("scan_ignore_packages").toStringList()

        if (!ensureSettingsLayout()) return SettingsActionResult(false, "Не удалось подготовить папку settings перед импортом.")
        val configOk = writeConfigRaw(configRaw)
        val trustedOk = writeList(PortGuardPaths.TRUSTED_PACKAGES, trustedPackages)
        val uidOk = writeList(PortGuardPaths.TRUSTED_UIDS, trustedUids)
        val killOk = writeList(PortGuardPaths.KILL_EXCEPTIONS, killExceptions)
        val scanOk = writeList(PortGuardPaths.SCAN_IGNORE_PACKAGES, scanIgnore)
        val success = configOk && trustedOk && uidOk && killOk && scanOk
        return SettingsActionResult(
            success = success,
            message = if (success) "Настройки импортированы из резервной копии." else "Не удалось полностью импортировать настройки из резервной копии.",
            path = if (success) PortGuardPaths.SETTINGS_BACKUP_LATEST_JSON else null,
        )
    }

    private suspend fun mutateConfig(mutator: (JSONObject) -> Unit): Boolean =
        configMutationMutex.withLock {
            val json = loadMutableConfigJson() ?: return@withLock false
            mutator(json)
            writeConfigRawUnlocked(json.toString(2))
        }

    private suspend fun loadMutableConfigJson(): JSONObject? {
        if (!ensureSettingsLayout()) return null
        val existing = readConfigRaw().orEmpty().trim()
        if (existing.isBlank()) return buildDefaultConfigJson()
        // Never replace a malformed existing config with defaults as a side effect
        // of changing one toggle. Surface the failure and preserve the file.
        return runCatching { JSONObject(existing) }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = opt(i)?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
