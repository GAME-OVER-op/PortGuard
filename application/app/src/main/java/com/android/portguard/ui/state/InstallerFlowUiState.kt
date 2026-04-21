package com.android.portguard.ui.state

import com.android.portguard.data.BundledModuleInfo
import com.android.portguard.data.ModuleInstallerType

enum class InstallerStep {
    WELCOME,
    REQUIREMENTS,
    UPDATE_PENDING,
    INSTALL,
    READY,
}

enum class CompatibilityState {
    UNKNOWN,
    SUPPORTED,
    UNSUPPORTED_ANDROID,
    UNSUPPORTED_ARCH,
}

data class InstallerFlowUiState(
    val step: InstallerStep = InstallerStep.WELCOME,
    val loading: Boolean = false,
    val startupLoading: Boolean = true,
    val welcomeCompleted: Boolean = false,
    val rootGranted: Boolean = false,
    val compatibilityState: CompatibilityState = CompatibilityState.UNKNOWN,
    val compatibilityMessage: String = "",
    val installerType: ModuleInstallerType = ModuleInstallerType.MANUAL,
    val assetPresent: Boolean = false,
    val bundledModuleInfo: BundledModuleInfo? = null,
    val installedModuleInfo: BundledModuleInfo? = null,
    val moduleInstalled: Boolean = false,
    val updateAvailable: Boolean = false,
    val stagedUpdateFound: Boolean = false,
    val statusMessage: String = "",
    val installLog: String = "",
    val installError: String? = null,
    val installSuccess: Boolean = false,
    val installing: Boolean = false,
    val rebooting: Boolean = false,
    val installProgress: Int = 0,
    val installPhase: String = "",
)
