package com.android.portguard.data

import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell

data class ModuleEnvironmentStatus(
    val rootGranted: Boolean = false,
    val moduleDirectoryExists: Boolean = false,
    val settingsDirectoryExists: Boolean = false,
    val configExists: Boolean = false,
    val stagedUpdateExists: Boolean = false,
    val configPreview: String = "",
    val installedModuleInfo: BundledModuleInfo? = null,
    val lastError: String? = null,
)

class ModuleStatusRepository(
    private val rootShell: RootShell,
    private val settingsRepository: SettingsFileRepository,
) {
    suspend fun inspect(readConfigPreview: Boolean = true): ModuleEnvironmentStatus {
        val rootGranted = rootShell.testRoot()
        if (!rootGranted) {
            return ModuleEnvironmentStatus(
                rootGranted = false,
                lastError = "Root-доступ не получен",
            )
        }

        val moduleDirExists = rootShell.fileExists(PortGuardPaths.MODULE_DIR)
        val settingsDirExists = rootShell.fileExists(PortGuardPaths.SETTINGS_DIR)
        val configExists = rootShell.fileExists(PortGuardPaths.CONFIG_JSON)
        val stagedUpdateExists = rootShell.fileExists(PortGuardPaths.MODULE_UPDATE_DIR)

        val configPreview = if (readConfigPreview && configExists) {
            settingsRepository.readConfigRaw().orEmpty().trim().take(1200)
        } else {
            ""
        }

        val installedModuleInfo = if (moduleDirExists) {
            rootShell.readText(PortGuardPaths.MODULE_PROP)?.let(::parseModuleProp)
        } else {
            null
        }

        return ModuleEnvironmentStatus(
            rootGranted = true,
            moduleDirectoryExists = moduleDirExists,
            settingsDirectoryExists = settingsDirExists,
            configExists = configExists,
            stagedUpdateExists = stagedUpdateExists,
            configPreview = configPreview,
            installedModuleInfo = installedModuleInfo,
            lastError = null,
        )
    }
}


private fun parseModuleProp(raw: String): BundledModuleInfo {
    val map = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith('#') && it.contains('=') }
        .associate {
            val idx = it.indexOf('=')
            it.substring(0, idx).trim() to it.substring(idx + 1).trim()
        }
    return BundledModuleInfo(
        id = map["id"].orEmpty(),
        name = map["name"].orEmpty(),
        version = map["version"].orEmpty(),
        versionCode = map["versionCode"]?.toIntOrNull(),
        author = map["author"].orEmpty(),
        description = map["description"].orEmpty(),
    )
}
