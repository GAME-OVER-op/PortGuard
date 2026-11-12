package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import org.json.JSONArray
import org.json.JSONObject

enum class ActiveProtectionMode(val raw: String) {
    ON("on"),
    OFF("off");

    companion object {
        fun fromRaw(value: String?): ActiveProtectionMode = when (value?.trim()?.lowercase()) {
            OFF.raw -> OFF
            else -> ON
        }
    }
}

enum class ReactionMode(val raw: String) {
    OFF("off"),
    FORCE_STOP("force_stop");

    companion object {
        fun fromRaw(value: String?): ReactionMode = when (value?.trim()?.lowercase()) {
            FORCE_STOP.raw -> FORCE_STOP
            else -> OFF
        }
    }
}

data class PortGuardConfig(
    val chainName: String = "PORTGUARD",
    val stateDir: String = PortGuardPaths.STATE_DIR,
    val activeProtection: ActiveProtectionMode = ActiveProtectionMode.ON,
    val learningMode: Boolean = false,
    val reactionMode: ReactionMode = ReactionMode.OFF,
    val protectLoopbackOnly: Boolean = false,
    val tcp4Enabled: Boolean = true,
    val udp4Enabled: Boolean = true,
    val tcp6Enabled: Boolean = true,
    val udp6Enabled: Boolean = true,
    val resolveProcessDetails: Boolean = false,
    val suspiciousUniquePorts: Int = 2,
    val suspiciousAttempts: Int = 3,
    val suspiciousRuleHits: Int = 3,
    val scanWindowSecs: Int = 60,
    val warnCooldownSecs: Int = 15,
    val reactionCooldownSecs: Int = 60,
    val loopIntervalMs: Int = 3000,
    val reloadCheckSecs: Int = 30,
    val maxRules: Int = 100000,
    val autoDiscoverPackages: Boolean = true,
    val packageUidSources: List<String> = listOf("/data/system/packages.list"),
    val packageRefreshSecs: Int = 300,
    val summaryPortLimit: Int = 24,
    val counterRefreshLoops: Int = 5,
    val userAppsOnly: Boolean = true,
    val networkRefreshSecs: Int = 30,
    val firewallReconcileSecs: Int = 300,
    val udpStabilizeSecs: Int = 6,
    val udpEphemeralStabilizeSecs: Int = 45,
    val udpGraceSecs: Int = 12,
    val activeSelfTestEnabled: Boolean = true,
    val selfTestTimeoutMs: Int = 1500,
    val ignoredOwnerPackages: List<String> = emptyList(),
    val rawConfig: String = "",
)

internal fun effectiveCounterRefreshSecs(
    loopIntervalMs: Int,
    counterRefreshLoops: Int,
    reactionMode: ReactionMode,
): Int {
    val intervalMs = loopIntervalMs.coerceAtLeast(500).toLong() *
        counterRefreshLoops.coerceAtLeast(5).toLong()
    val baseSecs = ((intervalMs + 999L) / 1000L).coerceAtLeast(30L)
    val effective = if (reactionMode == ReactionMode.FORCE_STOP) baseSecs else baseSecs.coerceAtLeast(60L)
    return effective.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal fun buildDefaultConfigJson(): JSONObject = JSONObject().apply {
    put("chain_name", "PORTGUARD")
    put("state_dir", PortGuardPaths.STATE_DIR)
    put("loop_interval_ms", 3000)
    put("reload_check_secs", 30)
    put("active_protection", ActiveProtectionMode.ON.raw)
    put("protect_loopback_only", false)
    put("tcp4_enabled", true)
    put("udp4_enabled", true)
    put("tcp6_enabled", true)
    put("udp6_enabled", true)
    put("resolve_process_details", false)
    put("trusted_client_packages", JSONArray(listOf("com.android.portguard")))
    put("trusted_client_uids", JSONArray())
    put("kill_exceptions_packages", JSONArray())
    put("scan_ignore_packages", JSONArray())
    put("ignored_owner_packages", JSONArray())
    put("suspicious_unique_ports", 2)
    put("suspicious_attempts", 3)
    put("suspicious_rule_hits", 3)
    put("scan_window_secs", 60)
    put("warn_cooldown_secs", 15)
    put("reaction_cooldown_secs", 60)
    put("max_rules", 100000)
    put("auto_discover_packages", true)
    put("package_uid_sources", JSONArray(listOf("/data/system/packages.list")))
    put("package_refresh_secs", 300)
    put("summary_port_limit", 24)
    put("counter_refresh_loops", 5)
    put("learning_mode", false)
    put("reaction_mode", ReactionMode.OFF.raw)
    put("user_apps_only", true)
    put("network_refresh_secs", 30)
    put("firewall_reconcile_secs", 300)
    put("udp_stabilize_secs", 6)
    put("udp_ephemeral_stabilize_secs", 45)
    put("udp_grace_secs", 12)
    put("active_self_test_enabled", true)
    put("self_test_timeout_ms", 1500)
}

internal fun parseConfig(raw: String): PortGuardConfig {
    // Invalid JSON is an error, not an implicit factory reset. Callers that want
    // a fallback already wrap this parser with runCatching/getOrNull.
    val json = JSONObject(raw)
    val udpStabilizeSecs = json.optInt("udp_stabilize_secs", 6).coerceAtLeast(3)
    val udpEphemeralStabilizeSecs = json
        .optInt("udp_ephemeral_stabilize_secs", 45)
        .coerceAtLeast(udpStabilizeSecs)
    val reactionMode = ReactionMode.fromRaw(json.optString("reaction_mode"))
    val loopIntervalMs = json.optInt("loop_interval_ms", 3000).coerceAtLeast(500)
    val counterRefreshLoops = json.optInt("counter_refresh_loops", 5).coerceAtLeast(5)
    val minScanWindowSecs = effectiveCounterRefreshSecs(
        loopIntervalMs = loopIntervalMs,
        counterRefreshLoops = counterRefreshLoops,
        reactionMode = reactionMode,
    )
    return PortGuardConfig(
        chainName = json.optString("chain_name").ifBlank { "PORTGUARD" },
        stateDir = json.optString("state_dir").ifBlank { PortGuardPaths.STATE_DIR },
        activeProtection = ActiveProtectionMode.fromRaw(json.optString("active_protection")),
        learningMode = json.optBoolean("learning_mode", false),
        reactionMode = reactionMode,
        protectLoopbackOnly = json.optBoolean("protect_loopback_only", false),
        tcp4Enabled = json.optBoolean("tcp4_enabled", true),
        udp4Enabled = json.optBoolean("udp4_enabled", true),
        tcp6Enabled = json.optBoolean("tcp6_enabled", true),
        udp6Enabled = json.optBoolean("udp6_enabled", true),
        resolveProcessDetails = json.optBoolean("resolve_process_details", false),
        suspiciousUniquePorts = json.optInt("suspicious_unique_ports", 2).coerceAtLeast(1),
        suspiciousAttempts = json.optInt("suspicious_attempts", 3).coerceAtLeast(2),
        suspiciousRuleHits = json.optInt("suspicious_rule_hits", 3).coerceAtLeast(2),
        scanWindowSecs = json.optInt("scan_window_secs", 60).coerceAtLeast(minScanWindowSecs),
        warnCooldownSecs = json.optInt("warn_cooldown_secs", 15).coerceAtLeast(1),
        reactionCooldownSecs = json.optInt("reaction_cooldown_secs", 60).coerceAtLeast(5),
        loopIntervalMs = loopIntervalMs,
        reloadCheckSecs = json.optInt("reload_check_secs", 30).coerceAtLeast(30),
        maxRules = json.optInt("max_rules", 100000).coerceAtLeast(1000),
        autoDiscoverPackages = json.optBoolean("auto_discover_packages", true),
        packageUidSources = json.optJSONArray("package_uid_sources").toStringList().ifEmpty { listOf("/data/system/packages.list") },
        packageRefreshSecs = json.optInt("package_refresh_secs", 300).coerceAtLeast(120),
        summaryPortLimit = json.optInt("summary_port_limit", 24).coerceAtLeast(1),
        counterRefreshLoops = counterRefreshLoops,
        userAppsOnly = json.optBoolean("user_apps_only", true),
        networkRefreshSecs = json.optInt("network_refresh_secs", 30).coerceAtLeast(10),
        firewallReconcileSecs = json.optInt("firewall_reconcile_secs", 300).coerceAtLeast(60),
        udpStabilizeSecs = udpStabilizeSecs,
        udpEphemeralStabilizeSecs = udpEphemeralStabilizeSecs,
        udpGraceSecs = json.optInt("udp_grace_secs", 12).coerceAtLeast(3),
        activeSelfTestEnabled = json.optBoolean("active_self_test_enabled", true),
        selfTestTimeoutMs = json.optInt("self_test_timeout_ms", 1500).coerceAtLeast(250),
        ignoredOwnerPackages = json.optJSONArray("ignored_owner_packages").toStringList(),
        rawConfig = json.toString(2),
    )
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
