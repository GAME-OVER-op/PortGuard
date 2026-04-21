package com.android.portguard.ui.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.core.RootShell
import com.android.portguard.data.InstallerInfo
import com.android.portguard.data.ModuleInstallerRepository
import com.android.portguard.data.ModuleInstallerType
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.CompatibilityState
import com.android.portguard.ui.state.InstallerFlowUiState
import com.android.portguard.ui.state.InstallerStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class InstallerFlowViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("portguard_app", Application.MODE_PRIVATE)
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val installerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)
    private var installProgressJob: Job? = null

    private val welcomeDone = prefs.getBoolean(KEY_WELCOME_DONE, false)

    private val _uiState = MutableStateFlow(
        InstallerFlowUiState(
            step = if (welcomeDone) InstallerStep.REQUIREMENTS else InstallerStep.WELCOME,
            startupLoading = true,
            welcomeCompleted = welcomeDone,
            statusMessage = "Подготавливаем приложение к работе…",
        )
    )
    val uiState: StateFlow<InstallerFlowUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            delay(650)
            if (welcomeDone) {
                refreshEnvironment(autoStep = true, startup = true)
            } else {
                _uiState.value = _uiState.value.copy(
                    startupLoading = false,
                    step = InstallerStep.WELCOME,
                    statusMessage = "PortGuard готов к первичной настройке.",
                )
            }
        }
    }

    fun continueFromWelcome() {
        prefs.edit().putBoolean(KEY_WELCOME_DONE, true).apply()
        _uiState.value = _uiState.value.copy(
            startupLoading = false,
            welcomeCompleted = true,
            step = InstallerStep.REQUIREMENTS,
            statusMessage = "Для продолжения нужен root-доступ и совместимое устройство.",
        )
    }

    fun requestRootAndContinue() {
        refreshEnvironment(autoStep = true, startup = false)
    }

    fun refreshEnvironment(autoStep: Boolean = false, startup: Boolean = false) {
        _uiState.value = _uiState.value.copy(
            loading = !startup,
            startupLoading = startup || _uiState.value.startupLoading,
            installError = null,
            statusMessage = "Проверяем root-доступ, совместимость устройства и состояние модуля…",
        )
        viewModelScope.launch(Dispatchers.IO) {
            val env = moduleStatusRepository.inspect(readConfigPreview = false)
            val installer = if (env.rootGranted) {
                installerRepository.detectInstaller()
            } else {
                InstallerInfo(type = ModuleInstallerType.MANUAL, assetPresent = false)
            }
            val compatibility = evaluateCompatibility()
            val state = buildState(env, installer, compatibility, autoStep)
            _uiState.value = state.copy(startupLoading = false, loading = false)
        }
    }

    fun installModule() {
        val current = _uiState.value
        if (current.installing || !current.rootGranted || current.compatibilityState != CompatibilityState.SUPPORTED) return
        _uiState.value = current.copy(
            installing = true,
            installSuccess = false,
            installError = null,
            installProgress = 6,
            installPhase = "Подготавливаем установку",
            installLog = "Подготовка установки модуля…",
            statusMessage = "Идёт установка модуля PortGuard…",
        )
        startInstallProgressTicker()
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.installUsingDetectedInstaller()
            installProgressJob?.cancel()
            val env = moduleStatusRepository.inspect(readConfigPreview = false)
            val installer = if (env.rootGranted) installerRepository.detectInstaller() else InstallerInfo(type = ModuleInstallerType.MANUAL, assetPresent = false)
            val compatibility = evaluateCompatibility()
            val base = buildState(env, installer, compatibility, autoStep = true)
            _uiState.value = base.copy(
                loading = false,
                startupLoading = false,
                installing = false,
                installSuccess = result.success,
                installError = if (result.success) null else result.log,
                installLog = result.log,
                installProgress = if (result.success) 100 else 0,
                installPhase = if (result.success) "Установка завершена" else "Установка завершилась с ошибкой",
                statusMessage = if (result.success) "Установка завершена. Для применения модуля требуется перезагрузка устройства." else "Установка завершилась с ошибкой.",
                step = InstallerStep.INSTALL,
                stagedUpdateFound = base.stagedUpdateFound || result.requiresReboot,
            )
        }
    }


    fun onSystemBack() {
        val current = _uiState.value
        if (current.startupLoading || current.loading || current.installing || current.rebooting) return

        when (current.step) {
            InstallerStep.WELCOME -> Unit
            InstallerStep.REQUIREMENTS -> {
                if (current.welcomeCompleted) {
                    _uiState.value = current.copy(
                        step = InstallerStep.WELCOME,
                        statusMessage = "PortGuard готов к первичной настройке.",
                    )
                }
            }
            InstallerStep.UPDATE_PENDING -> Unit
            InstallerStep.INSTALL -> {
                if (!current.moduleInstalled) {
                    _uiState.value = current.copy(
                        step = InstallerStep.REQUIREMENTS,
                        statusMessage = "Для продолжения нужен root-доступ и совместимое устройство.",
                    )
                }
            }
            InstallerStep.READY -> Unit
        }
    }

    fun rebootDevice() {
        if (_uiState.value.rebooting) return
        _uiState.value = _uiState.value.copy(
            rebooting = true,
            installError = null,
            installLog = if (_uiState.value.installLog.isBlank()) "Отправка команды перезагрузки…" else _uiState.value.installLog,
            statusMessage = "Отправляется команда перезагрузки устройства…",
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.rebootDevice()
            _uiState.value = _uiState.value.copy(
                rebooting = false,
                installSuccess = result.success,
                installError = if (result.success) null else result.log,
                installLog = listOf(_uiState.value.installLog, result.log).filter { it.isNotBlank() }.joinToString("\n\n"),
                statusMessage = if (result.success) "Команда перезагрузки отправлена." else "Не удалось выполнить перезагрузку.",
            )
        }
    }

    private fun startInstallProgressTicker() {
        installProgressJob?.cancel()
        installProgressJob = viewModelScope.launch {
            val phases = listOf(
                10 to "Проверяем требования модуля",
                18 to "Определяем способ установки",
                32 to "Подготавливаем архив модуля",
                48 to "Проверяем Android 9+ и arm64",
                64 to "Настраиваем содержимое модуля",
                78 to "Применяем установку через менеджер",
                90 to "Завершаем установку",
                94 to "Ожидаем финальный ответ установщика",
            )
            for ((progress, phase) in phases) {
                if (!_uiState.value.installing) break
                _uiState.value = _uiState.value.copy(
                    installProgress = maxOf(_uiState.value.installProgress, progress),
                    installPhase = phase,
                )
                delay(550)
            }
            while (_uiState.value.installing) {
                val next = (_uiState.value.installProgress + 1).coerceAtMost(96)
                _uiState.value = _uiState.value.copy(
                    installProgress = next,
                    installPhase = if (next >= 96) "Почти готово" else _uiState.value.installPhase,
                )
                delay(400)
            }
        }
    }

    private fun buildState(
        env: com.android.portguard.data.ModuleEnvironmentStatus,
        installer: InstallerInfo,
        compatibility: Pair<CompatibilityState, String>,
        autoStep: Boolean,
    ): InstallerFlowUiState {
        val (compatibilityState, compatibilityMessage) = compatibility
        val updateAvailable = isModuleUpdateRequired(env.installedModuleInfo, installer.moduleInfo)
        val nextStep = when {
            !autoStep -> _uiState.value.step
            env.rootGranted && compatibilityState == CompatibilityState.SUPPORTED && env.stagedUpdateExists -> InstallerStep.UPDATE_PENDING
            env.rootGranted && compatibilityState == CompatibilityState.SUPPORTED && env.moduleDirectoryExists && !updateAvailable -> InstallerStep.READY
            env.rootGranted && compatibilityState == CompatibilityState.SUPPORTED -> InstallerStep.INSTALL
            else -> InstallerStep.REQUIREMENTS
        }
        return _uiState.value.copy(
            loading = false,
            step = nextStep,
            rootGranted = env.rootGranted,
            compatibilityState = compatibilityState,
            compatibilityMessage = compatibilityMessage,
            installerType = installer.type,
            assetPresent = installer.assetPresent,
            bundledModuleInfo = installer.moduleInfo,
            installedModuleInfo = env.installedModuleInfo,
            moduleInstalled = env.moduleDirectoryExists,
            updateAvailable = updateAvailable,
            stagedUpdateFound = env.stagedUpdateExists,
            statusMessage = buildStatusMessage(env, installer, compatibilityState, compatibilityMessage, updateAvailable),
        )
    }

    private fun buildStatusMessage(
        env: com.android.portguard.data.ModuleEnvironmentStatus,
        installer: InstallerInfo,
        compatibilityState: CompatibilityState,
        compatibilityMessage: String,
        updateAvailable: Boolean,
    ): String {
        if (!env.rootGranted) return "Root-доступ не получен. Нажми кнопку ниже и разреши приложению доступ суперпользователя."
        if (compatibilityState != CompatibilityState.SUPPORTED) return compatibilityMessage
        if (env.stagedUpdateExists) return "Найдено незавершённое обновление модуля. Для завершения установки требуется перезагрузка устройства."
        if (env.moduleDirectoryExists && !updateAvailable) return "Модуль уже установлен и обновление не требуется."
        if (updateAvailable) {
            val installed = env.installedModuleInfo?.version.orEmpty().ifBlank { "неизвестно" }
            val bundled = installer.moduleInfo?.version.orEmpty().ifBlank { "неизвестно" }
            return "Обнаружена старая версия модуля: $installed → $bundled. Для продолжения нужно обновить модуль."
        }
        return buildString {
            append("Root получен • ")
            append("совместимость подтверждена • ")
            append("установщик: ")
            append(installer.type.label)
            append(" • встроенный модуль: ")
            append(if (installer.assetPresent) "готов" else "не найден")
        }
    }

    private fun isModuleUpdateRequired(
        installed: com.android.portguard.data.BundledModuleInfo?,
        bundled: com.android.portguard.data.BundledModuleInfo?,
    ): Boolean {
        if (installed == null || bundled == null) return false
        val installedCode = installed.versionCode
        val bundledCode = bundled.versionCode
        return when {
            installedCode != null && bundledCode != null -> installedCode < bundledCode
            installed.version.isNotBlank() && bundled.version.isNotBlank() -> installed.version != bundled.version
            else -> false
        }
    }

    private fun evaluateCompatibility(): Pair<CompatibilityState, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return CompatibilityState.UNSUPPORTED_ANDROID to "PortGuard поддерживает только Android 9 и новее. На этом устройстве установка недоступна."
        }
        val allAbis = (Build.SUPPORTED_64_BIT_ABIS.toList() + Build.SUPPORTED_ABIS.toList()).distinct()
        val arm64 = allAbis.any { it.contains("arm64", ignoreCase = true) }
        if (!arm64) {
            return CompatibilityState.UNSUPPORTED_ARCH to "PortGuard поддерживает только устройства arm64. На текущей архитектуре модуль работать не будет."
        }
        return CompatibilityState.SUPPORTED to "Устройство совместимо: Android ${Build.VERSION.SDK_INT}, arm64."
    }

    companion object {
        private const val KEY_WELCOME_DONE = "welcome_completed"
    }
}
