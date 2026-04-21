package com.android.portguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.ui.screens.InstallModuleScreen
import com.android.portguard.ui.screens.MainHubScreen
import com.android.portguard.ui.screens.RequirementsScreen
import com.android.portguard.ui.screens.StartupLoadingScreen
import com.android.portguard.ui.screens.UpdatePendingScreen
import com.android.portguard.ui.screens.WelcomeScreen
import com.android.portguard.ui.state.InstallerStep
import com.android.portguard.ui.theme.PortGuardAppTheme
import com.android.portguard.ui.viewmodel.InstallerFlowViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = ContextCompat.getColor(this, R.color.pg_system_bar)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.pg_nav_bar)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            PortGuardAppTheme {
                PortGuardApp()
            }
        }
    }
}

@Composable
private fun PortGuardApp(
    vm: InstallerFlowViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = true) {
        vm.onSystemBack()
    }

    AnimatedContent(
        targetState = state.startupLoading,
        transitionSpec = {
            (fadeIn()).togetherWith(fadeOut())
        },
        contentAlignment = Alignment.Center,
        label = "startup_gate",
    ) { loading ->
        if (loading) {
            StartupLoadingScreen()
        } else {
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    screenTransition(initialState, targetState)
                },
                contentAlignment = Alignment.Center,
                label = "installer_step_transition",
            ) { step ->
                when (step) {
                    InstallerStep.WELCOME -> WelcomeScreen(
                        onContinue = vm::continueFromWelcome,
                    )

                    InstallerStep.REQUIREMENTS -> RequirementsScreen(
                        state = state,
                        onRequestRoot = vm::requestRootAndContinue,
                    )

                    InstallerStep.UPDATE_PENDING -> UpdatePendingScreen(
                        state = state,
                        onReboot = vm::rebootDevice,
                    )

                    InstallerStep.INSTALL -> InstallModuleScreen(
                        state = state,
                        onInstall = vm::installModule,
                        onReboot = vm::rebootDevice,
                    )

                    InstallerStep.READY -> MainHubScreen()
                }
            }
        }
    }
}

private fun screenTransition(from: InstallerStep, to: InstallerStep): ContentTransform {
    val direction = if (to.ordinal >= from.ordinal) 1 else -1
    return (
        slideInHorizontally(initialOffsetX = { fullWidth -> fullWidth / 5 * direction }) + fadeIn()
        ).togetherWith(
        slideOutHorizontally(targetOffsetX = { fullWidth -> -(fullWidth / 7) * direction }) + fadeOut()
    )
}
