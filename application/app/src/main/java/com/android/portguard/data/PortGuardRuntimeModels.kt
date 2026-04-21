package com.android.portguard.data

import org.json.JSONArray
import org.json.JSONObject

data class PortGuardRuntimeStatus(
    val stateFilesPresent: Boolean = false,
    val statusFileFound: Boolean = false,
    val summaryFileFound: Boolean = false,
    val incidentsFileFound: Boolean = false,
    val activeProtection: String = "unknown",
    val standby: Boolean = false,
    val protectedPorts: Int = 0,
    val activeRules: Int = 0,
    val backend: String = "",
    val blockedPorts: List<Int> = emptyList(),
    val message: String = "",
    val lastUpdated: String = "",
    val summaryRaw: String = "",
)

data class PortGuardIncident(
    val timestamp: String = "",
    val level: String = "INFO",
    val uid: String = "",
    val packages: List<String> = emptyList(),
    val ports: List<String> = emptyList(),
    val action: String = "",
    val message: String = "",
    val rawLine: String = "",
)

internal fun parseRuntimeStatus(
    statusRaw: String?,
    summaryRaw: String?,
    incidentsExists: Boolean,
): PortGuardRuntimeStatus {
    val statusJson = runCatching { statusRaw?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull()
    val summaryJson = runCatching { summaryRaw?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull()

    val blockedPorts = jsonArrayToIntList(
        summaryJson?.optJSONArray("blocked_ports")
            ?: statusJson?.optJSONArray("blocked_ports")
    )

    val activeProtection = firstNonBlank(
        summaryJson?.optString("active_protection"),
        statusJson?.optString("active_protection"),
        if (statusJson?.optBoolean("active", false) == true) "on" else null,
    ) ?: "unknown"

    val standby = summaryJson?.optBoolean("standby")
        ?: statusJson?.optBoolean("standby")
        ?: false

    val protectedPorts = firstPositiveInt(
        summaryJson?.optInt("protected_ports"),
        summaryJson?.optInt("ports"),
        statusJson?.optInt("protected_ports"),
        blockedPorts.size,
    )

    val activeRules = firstPositiveInt(
        summaryJson?.optInt("active_rules"),
        summaryJson?.optInt("rules"),
        statusJson?.optInt("active_rules"),
        statusJson?.optInt("rules"),
    )

    val backend = firstNonBlank(
        summaryJson?.optString("backend"),
        statusJson?.optString("backend"),
    ).orEmpty()

    val message = firstNonBlank(
        summaryJson?.optString("message"),
        statusJson?.optString("message"),
        statusJson?.optString("status_text"),
    ).orEmpty()

    val lastUpdated = firstNonBlank(
        summaryJson?.optString("updated_at"),
        summaryJson?.optString("last_updated"),
        statusJson?.optString("updated_at"),
        statusJson?.optString("last_updated"),
    ).orEmpty()

    val trimmedSummary = summaryRaw.orEmpty().trim().take(1400)

    return PortGuardRuntimeStatus(
        stateFilesPresent = !statusRaw.isNullOrBlank() || !summaryRaw.isNullOrBlank() || incidentsExists,
        statusFileFound = !statusRaw.isNullOrBlank(),
        summaryFileFound = !summaryRaw.isNullOrBlank(),
        incidentsFileFound = incidentsExists,
        activeProtection = activeProtection,
        standby = standby,
        protectedPorts = protectedPorts,
        activeRules = activeRules,
        backend = backend,
        blockedPorts = blockedPorts,
        message = message,
        lastUpdated = lastUpdated,
        summaryRaw = trimmedSummary,
    )
}

internal fun parseIncidentsJsonl(raw: String?): List<PortGuardIncident> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                PortGuardIncident(
                    timestamp = firstNonBlank(
                        json.optString("timestamp"),
                        json.optString("ts"),
                        json.optString("time"),
                    ).orEmpty(),
                    level = firstNonBlank(json.optString("level"), json.optString("severity")).orEmpty().ifBlank { "INFO" },
                    uid = json.opt("uid")?.toString().orEmpty(),
                    packages = jsonArrayToStringList(
                        json.optJSONArray("packages") ?: json.optJSONArray("package_names")
                    ).ifEmpty {
                        firstNonBlank(json.optString("package"), json.optString("pkg"))?.let { listOf(it) } ?: emptyList()
                    },
                    ports = jsonArrayToStringList(json.optJSONArray("ports")),
                    action = firstNonBlank(json.optString("action"), json.optString("reaction")).orEmpty(),
                    message = firstNonBlank(json.optString("message"), json.optString("msg")).orEmpty(),
                    rawLine = line,
                )
            }.getOrElse {
                PortGuardIncident(rawLine = line, message = line)
            }
        }
        .toList()
}

private fun jsonArrayToIntList(array: JSONArray?): List<Int> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.opt(i)?.toString()?.toIntOrNull() ?: continue
            add(value)
        }
    }
}

private fun jsonArrayToStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val value = array.opt(i)?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) add(value)
        }
    }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

private fun firstPositiveInt(vararg values: Int?): Int =
    values.firstOrNull { (it ?: 0) > 0 } ?: 0
