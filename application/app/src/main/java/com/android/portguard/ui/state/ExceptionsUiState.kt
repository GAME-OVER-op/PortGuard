package com.android.portguard.ui.state

import com.android.portguard.data.InstalledAppEntry

enum class ExceptionsTab {
    TRUSTED_PACKAGES,
    KILL_EXCEPTIONS,
    SCAN_IGNORE,
    TRUSTED_UIDS,
}

data class ExceptionsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val selectedTab: ExceptionsTab = ExceptionsTab.TRUSTED_PACKAGES,
    val searchQuery: String = "",
    val installedApps: List<InstalledAppEntry> = emptyList(),
    val trustedPackages: List<String> = emptyList(),
    val killExceptions: List<String> = emptyList(),
    val scanIgnorePackages: List<String> = emptyList(),
    val trustedUids: List<String> = emptyList(),
    val uidDraft: String = "",
    val infoMessage: String? = null,
    val errorMessage: String? = null,
) {
    val selectedPackageList: List<String>
        get() = when (selectedTab) {
            ExceptionsTab.TRUSTED_PACKAGES -> trustedPackages
            ExceptionsTab.KILL_EXCEPTIONS -> killExceptions
            ExceptionsTab.SCAN_IGNORE -> scanIgnorePackages
            ExceptionsTab.TRUSTED_UIDS -> emptyList()
        }

    val filteredApps: List<InstalledAppEntry>
        get() {
            if (selectedTab == ExceptionsTab.TRUSTED_UIDS) return emptyList()
            val query = searchQuery.trim().lowercase()
            if (query.isBlank()) return installedApps
            return installedApps.filter {
                it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
            }
        }
}
