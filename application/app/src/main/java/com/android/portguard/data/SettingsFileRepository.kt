package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SettingsFileRepository(
    private val rootShell: RootShell,
) {
    suspend fun readConfigRaw(): String? = rootShell.readText(PortGuardPaths.CONFIG_JSON)

    suspend fun writeConfigRaw(content: String): Boolean {
        return rootShell.writeTextAtomic(PortGuardPaths.CONFIG_JSON, content)
    }

    suspend fun readConfig(): PortGuardConfig? {
        val raw = readConfigRaw() ?: return null
        return runCatching { parseConfig(raw) }.getOrNull()
    }

    suspend fun ensureDefaultConfig(): Boolean {
        if (!ensureSettingsLayout()) return false
        if (rootShell.fileExists(PortGuardPaths.CONFIG_JSON)) return true
        return writeConfigRaw(buildDefaultConfigJson().toString(2))
    }

    suspend fun updateDashboardConfig(
        activeProtection: ActiveProtectionMode,
        learningMode: Boolean,
        reactionMode: ReactionMode,
        protectLoopbackOnly: Boolean,
    ): Boolean {
        val json = loadMutableConfigJson() ?: return false
        json.put("active_protection", activeProtection.raw)
        json.put("learning_mode", learningMode)
        json.put("reaction_mode", reactionMode.raw)
        json.put("protect_loopback_only", protectLoopbackOnly)
        return writeConfigRaw(json.toString(2))
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
    ): Boolean {
        val json = loadMutableConfigJson() ?: return false
        json.put("suspicious_unique_ports", suspiciousUniquePorts.coerceAtLeast(1))
        json.put("suspicious_attempts", suspiciousAttempts.coerceAtLeast(1))
        json.put("suspicious_rule_hits", suspiciousRuleHits.coerceAtLeast(1))
        json.put("scan_window_secs", scanWindowSecs.coerceAtLeast(1))
        json.put("warn_cooldown_secs", warnCooldownSecs.coerceAtLeast(1))
        json.put("reaction_cooldown_secs", reactionCooldownSecs.coerceAtLeast(5))
        json.put("loop_interval_ms", loopIntervalMs.coerceAtLeast(500))
        json.put("reload_check_secs", reloadCheckSecs.coerceAtLeast(30))
        json.put("package_refresh_secs", packageRefreshSecs.coerceAtLeast(10))
        json.put("counter_refresh_loops", counterRefreshLoops.coerceAtLeast(1))
        json.put("resolve_process_details", resolveProcessDetails)
        json.put("protect_loopback_only", protectLoopbackOnly)
        return writeConfigRaw(json.toString(2))
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
    ): Boolean {
        val json = loadMutableConfigJson() ?: return false
        json.put("tcp4_enabled", tcp4Enabled)
        json.put("udp4_enabled", udp4Enabled)
        json.put("tcp6_enabled", tcp6Enabled)
        json.put("udp6_enabled", udp6Enabled)
        json.put("user_apps_only", userAppsOnly)
        json.put("network_refresh_secs", networkRefreshSecs.coerceAtLeast(2))
        json.put("active_self_test_enabled", activeSelfTestEnabled)
        json.put("self_test_timeout_ms", selfTestTimeoutMs.coerceAtLeast(250))
        return writeConfigRaw(json.toString(2))
    }

    suspend fun updateSystemConfig(
        chainName: String,
        stateDir: String,
        maxRules: Int,
        autoDiscoverPackages: Boolean,
        packageUidSources: List<String>,
        summaryPortLimit: Int,
        ignoredOwnerPackages: List<String>,
    ): Boolean {
        val json = loadMutableConfigJson() ?: return false
        json.put("chain_name", chainName.ifBlank { "PORTGUARD" })
        json.put("state_dir", stateDir.ifBlank { PortGuardPaths.STATE_DIR })
        json.put("max_rules", maxRules.coerceAtLeast(1000))
        json.put("auto_discover_packages", autoDiscoverPackages)
        json.put("package_uid_sources", JSONArray(packageUidSources.filter { it.isNotBlank() }.distinct()))
        json.put("summary_port_limit", summaryPortLimit.coerceAtLeast(1))
        json.put("ignored_owner_packages", JSONArray(ignoredOwnerPackages.filter { it.isNotBlank() }.distinct()))
        return writeConfigRaw(json.toString(2))
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

    suspend fun writeList(path: String, values: List<String>): Boolean {
        val normalized = values
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(separator = "\n")

        val payload = if (normalized.isBlank()) "" else "${normalized}\n"
        return rootShell.writeTextAtomic(path = path, content = payload)
    }

    suspend fun ensureSettingsLayout(): Boolean {
        val created = rootShell.mkdirs(PortGuardPaths.SETTINGS_DIR)
        val stateCreated = rootShell.mkdirs(PortGuardPaths.STATE_DIR)
        return created && stateCreated
    }

    suspend fun applyPreset(preset: PortGuardPreset): SettingsActionResult {
        val json = loadMutableConfigJson() ?: return SettingsActionResult(false, "Не удалось загрузить config.json для применения пресета.")
        when (preset) {
            PortGuardPreset.BALANCED -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.OFF.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 2)
                json.put("suspicious_rule_hits", 1)
                json.put("scan_window_secs", 10)
                json.put("warn_cooldown_secs", 15)
                json.put("reaction_cooldown_secs", 60)
                json.put("loop_interval_ms", 3000)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 60)
                json.put("counter_refresh_loops", 2)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 10)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
            PortGuardPreset.STRICT -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.OFF.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 1)
                json.put("suspicious_rule_hits", 1)
                json.put("scan_window_secs", 12)
                json.put("warn_cooldown_secs", 10)
                json.put("reaction_cooldown_secs", 45)
                json.put("loop_interval_ms", 2500)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 45)
                json.put("counter_refresh_loops", 1)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 20)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
            PortGuardPreset.AGGRESSIVE -> {
                json.put("active_protection", ActiveProtectionMode.ON.raw)
                json.put("learning_mode", false)
                json.put("reaction_mode", ReactionMode.FORCE_STOP.raw)
                json.put("protect_loopback_only", false)
                json.put("suspicious_unique_ports", 2)
                json.put("suspicious_attempts", 1)
                json.put("suspicious_rule_hits", 1)
                json.put("scan_window_secs", 15)
                json.put("warn_cooldown_secs", 5)
                json.put("reaction_cooldown_secs", 30)
                json.put("loop_interval_ms", 2000)
                json.put("reload_check_secs", 30)
                json.put("package_refresh_secs", 30)
                json.put("counter_refresh_loops", 1)
                json.put("resolve_process_details", false)
                json.put("tcp4_enabled", true)
                json.put("udp4_enabled", true)
                json.put("tcp6_enabled", true)
                json.put("udp6_enabled", true)
                json.put("user_apps_only", true)
                json.put("network_refresh_secs", 10)
                json.put("active_self_test_enabled", true)
                json.put("self_test_timeout_ms", 1500)
            }
        }
        return if (writeConfigRaw(json.toString(2))) {
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

    private suspend fun loadMutableConfigJson(): JSONObject? {
        if (!ensureSettingsLayout()) return null
        val existing = readConfigRaw().orEmpty().trim()
        return runCatching {
            if (existing.isBlank()) buildDefaultConfigJson() else JSONObject(existing)
        }.getOrElse { buildDefaultConfigJson() }
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
