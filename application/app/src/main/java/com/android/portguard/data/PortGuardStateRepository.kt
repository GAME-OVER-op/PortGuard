package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell

class PortGuardStateRepository(
    private val rootShell: RootShell,
) {
    suspend fun readRuntimeStatus(): PortGuardRuntimeStatus {
        val statusRaw = rootShell.readText(PortGuardPaths.STATUS_JSON)
        val summaryRaw = rootShell.readText(PortGuardPaths.SUMMARY_JSON)
        val incidentsExists = rootShell.fileExists(PortGuardPaths.INCIDENTS_JSONL)
        return parseRuntimeStatus(statusRaw = statusRaw, summaryRaw = summaryRaw, incidentsExists = incidentsExists)
    }

    suspend fun readIncidents(limit: Int = 120): List<PortGuardIncident> {
        val raw = rootShell.readText(PortGuardPaths.INCIDENTS_JSONL)
        return parseIncidentsJsonl(raw)
            .takeLast(limit)
            .asReversed()
    }

    suspend fun clearIncidents(): Boolean {
        return rootShell.writeTextAtomic(PortGuardPaths.INCIDENTS_JSONL, "")
    }
}
