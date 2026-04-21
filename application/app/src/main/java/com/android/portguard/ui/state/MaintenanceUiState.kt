package com.android.portguard.ui.state

import com.android.portguard.data.ModuleInstallerType

data class MaintenanceUiState(
    val loading: Boolean = false,
    val working: Boolean = false,
    val rootGranted: Boolean = false,
    val moduleInstalled: Boolean = false,
    val configFound: Boolean = false,
    val installerLabel: String = ModuleInstallerType.MANUAL.label,
    val assetPresent: Boolean = false,
    val statusText: String = "",
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val actionLog: String = "",
    val backupPath: String = "",
)
