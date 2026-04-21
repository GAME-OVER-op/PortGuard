package com.android.portguard.ui.state

import com.android.portguard.core.PortGuardPaths

enum class RootAccessState {
    UNKNOWN,
    CHECKING,
    GRANTED,
    DENIED,
}

data class RootModuleUiState(
    val checking: Boolean = false,
    val rootState: RootAccessState = RootAccessState.UNKNOWN,
    val moduleInstalled: Boolean = false,
    val settingsDirectoryFound: Boolean = false,
    val configFound: Boolean = false,
    val stagedUpdateFound: Boolean = false,
    val assetPresent: Boolean = false,
    val configPreview: String = "",
    val statusText: String = "",
    val modulePath: String = PortGuardPaths.MODULE_DIR,
    val settingsPath: String = PortGuardPaths.SETTINGS_DIR,
    val installerLabel: String = "",
    val installing: Boolean = false,
    val rebooting: Boolean = false,
    val installLog: String = "",
    val installError: String? = null,
    val installSuccess: Boolean = false,
    val exportedZipPath: String? = null,
)
