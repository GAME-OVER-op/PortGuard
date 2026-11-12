package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell

class PortGuardStateRepository(
    private val rootShell: RootShell,
) {
    private fun statePath(stateDir: String, fileName: String): String {
        val normalized = stateDir.trim().trimEnd('/').ifBlank { PortGuardPaths.STATE_DIR }
        return "$normalized/$fileName"
    }

    suspend fun readRuntimeStatus(stateDir: String = PortGuardPaths.STATE_DIR): PortGuardRuntimeStatus {
        val statusRaw = rootShell.readText(statePath(stateDir, "status.json"))
        val summaryRaw = rootShell.readText(statePath(stateDir, "summary.json"))
        val incidentsExists = rootShell.fileExists(statePath(stateDir, "incidents.jsonl"))
        return parseRuntimeStatus(statusRaw = statusRaw, summaryRaw = summaryRaw, incidentsExists = incidentsExists)
    }

    suspend fun readIncidents(
        limit: Int = 120,
        stateDir: String = PortGuardPaths.STATE_DIR,
    ): List<PortGuardIncident> {
        val raw = rootShell.readTextTail(statePath(stateDir, "incidents.jsonl"), limit.coerceAtLeast(1))
        return parseIncidentsJsonl(raw)
            .takeLast(limit)
            .asReversed()
    }

    suspend fun clearIncidents(stateDir: String = PortGuardPaths.STATE_DIR): Boolean {
        val path = statePath(stateDir, "incidents.jsonl")
        // Truncate in place instead of atomic rename: the Rust daemon may already
        // hold this file open for append, and replacing the inode could otherwise
        // send a concurrent incident into the old unlinked file.
        return rootShell.exec(": > ${rootShell.shellQuote(path)}").isSuccess
    }
}
