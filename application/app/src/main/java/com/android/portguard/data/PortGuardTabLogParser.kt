package com.android.portguard.data

import java.util.Locale

data class FirewallProtoStatus(
    val proto: String,
    val status: String,
    val details: String = "",
)

enum class FirewallHealth {
    WAITING,
    GOOD,
    PARTIAL,
    ERROR,
}

data class TabLogSummary(
    val protectionEnabled: Boolean = false,
    val portsCount: Int = 0,
    val groupedRules: Int = 0,
    val blockedPorts: List<Int> = emptyList(),
    val candidateApps: Int = 0,
    val localSnapshots: String = "",
    val reapplyReason: String = "",
    val selfTest: String = "",
    val protoStatuses: List<FirewallProtoStatus> = emptyList(),
    val rawMap: Map<String, String> = emptyMap(),
) {
    val firewallHealth: FirewallHealth
        get() {
            if (!protectionEnabled) return FirewallHealth.WAITING
            val rawCaps = rawMap["fw_caps"].orEmpty().trim()
            if (rawCaps.isBlank() || rawCaps.equals("none", ignoreCase = true) || protoStatuses.isEmpty()) {
                return FirewallHealth.WAITING
            }
            val goodStates = setOf("ok")
            val waitingStates = setOf("idle", "waiting")
            val badStates = setOf("unsupported", "apply-failed", "failed", "error", "busy", "timed-out")
            val good = protoStatuses.count { it.status in goodStates }
            val waiting = protoStatuses.count { it.status in waitingStates }
            val bad = protoStatuses.count { it.status in badStates }
            if (good == 0 && waiting == protoStatuses.size) return FirewallHealth.WAITING
            return when {
                good == protoStatuses.size -> FirewallHealth.GOOD
                good > 0 && bad == 0 -> FirewallHealth.PARTIAL
                good > 0 -> FirewallHealth.PARTIAL
                bad > 0 -> FirewallHealth.ERROR
                else -> FirewallHealth.WAITING
            }
        }
}

fun parseTabLogSummary(raw: String?): TabLogSummary {
    if (raw.isNullOrBlank()) return TabLogSummary()

    val map = linkedMapOf<String, String>()
    val lineRegex = Regex("^\\|\\s*(.+?)\\s*:\\s*(.*?)\\s*\\|$")
    raw.lineSequence()
        .map { it.trim() }
        .forEach { line ->
            val match = lineRegex.matchEntire(line) ?: return@forEach
            val key = match.groupValues[1].trim().lowercase(Locale.ROOT)
            val value = match.groupValues[2].trim()
            map[key] = value
        }

    val blockedPorts = parsePorts(map["blocked_ports"].orEmpty())
    val protoStatuses = parseProtoStatuses(map["fw_caps"].orEmpty())
    return TabLogSummary(
        protectionEnabled = map["status"]?.contains("on", ignoreCase = true) == true,
        portsCount = map["ports"]?.toIntOrNull() ?: 0,
        groupedRules = map["grouped_rules"]?.toIntOrNull() ?: 0,
        blockedPorts = blockedPorts,
        candidateApps = map["candidate_apps"]?.toIntOrNull() ?: 0,
        localSnapshots = map["local_snapshots"].orEmpty(),
        reapplyReason = map["reapply_reason"].orEmpty(),
        selfTest = map["self_test"].orEmpty(),
        protoStatuses = protoStatuses,
        rawMap = map,
    )
}

private fun parsePorts(raw: String): List<Int> {
    if (raw.isBlank() || raw.equals("[none]", true) || raw.equals("none", true)) return emptyList()
    return Regex("\\d+").findAll(raw).mapNotNull { it.value.toIntOrNull() }.toList()
}

private fun parseProtoStatuses(raw: String): List<FirewallProtoStatus> {
    if (raw.isBlank()) return emptyList()
    val regex = Regex("(tcp4|udp4|tcp6|udp6):([a-zA-Z0-9_-]+)([^;]*)")
    return regex.findAll(raw)
        .map {
            FirewallProtoStatus(
                proto = it.groupValues[1],
                status = it.groupValues[2].lowercase(Locale.ROOT),
                details = it.groupValues[3].trim().trimStart(':').trim(),
            )
        }
        .toList()
}
