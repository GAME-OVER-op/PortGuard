package com.android.portguard.data

import android.content.Context
import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream


enum class ModuleInstallerType(val label: String) {
    MAGISK("Magisk"),
    KERNEL_SU("KernelSU"),
    APATCH("APatch"),
    MANUAL("Ручная установка"),
}

data class InstallerInfo(
    val type: ModuleInstallerType,
    val assetPresent: Boolean,
    val moduleInfo: BundledModuleInfo? = null,
)

data class BundledModuleInfo(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val versionCode: Int? = null,
    val author: String = "",
    val description: String = "",
)

data class ModuleInstallResult(
    val success: Boolean,
    val log: String,
    val requiresReboot: Boolean = false,
    val exportedPath: String? = null,
)

private data class CachedInstaller(
    val info: InstallerInfo,
    val savedAtMs: Long,
)

class ModuleInstallerRepository(
    private val context: Context,
    private val rootShell: RootShell,
) {
    companion object {
        const val EMBEDDED_ASSET_NAME = "module.zip"
        const val EMBEDDED_PROP_ASSET_NAME = "module.prop"
        private const val INSTALLER_CACHE_TTL_MS = 60_000L

        @Volatile
        private var cachedInstaller: CachedInstaller? = null
    }

    suspend fun detectInstaller(forceRefresh: Boolean = false): InstallerInfo {
        val now = System.currentTimeMillis()
        cachedInstaller
            ?.takeIf { !forceRefresh && (now - it.savedAtMs) <= INSTALLER_CACHE_TTL_MS }
            ?.let { return it.info }

        val assetPresent = hasEmbeddedModuleAsset()
        val info = readBundledModuleInfo()
        val type = when {
            rootShell.commandExists("magisk") -> ModuleInstallerType.MAGISK
            rootShell.commandExists("ksud") -> ModuleInstallerType.KERNEL_SU
            rootShell.commandExists("apd") -> ModuleInstallerType.APATCH
            else -> ModuleInstallerType.MANUAL
        }
        return InstallerInfo(type = type, assetPresent = assetPresent, moduleInfo = info)
            .also { cachedInstaller = CachedInstaller(info = it, savedAtMs = now) }
    }

    suspend fun hasEmbeddedModuleAsset(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(EMBEDDED_ASSET_NAME).close()
            true
        }.getOrDefault(false)
    }

    suspend fun readBundledModuleInfo(): BundledModuleInfo? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(EMBEDDED_PROP_ASSET_NAME).bufferedReader().use { reader ->
                parseModuleProp(reader.readText())
            }
        }.getOrNull()
    }

    suspend fun installUsingDetectedInstaller(): ModuleInstallResult {
        val info = detectInstaller()
        if (!info.assetPresent) {
            return ModuleInstallResult(
                success = false,
                log = "В assets не найден $EMBEDDED_ASSET_NAME. Добавь ZIP модуля в app/src/main/assets/.",
            )
        }

        return when (info.type) {
            ModuleInstallerType.MAGISK -> runInstallCommand("magisk --install-module ${PortGuardPaths.TMP_MODULE_ZIP}")
            ModuleInstallerType.KERNEL_SU -> runInstallCommand("ksud module install ${PortGuardPaths.TMP_MODULE_ZIP}")
            ModuleInstallerType.APATCH -> runInstallCommand("apd module install ${PortGuardPaths.TMP_MODULE_ZIP}")
            ModuleInstallerType.MANUAL -> ModuleInstallResult(
                success = false,
                log = "Автоматический установщик не найден. Для продолжения нужен Magisk, KernelSU или APatch.",
            )
        }
    }

    suspend fun exportModuleZipToSdcard(): ModuleInstallResult {
        if (!hasEmbeddedModuleAsset()) {
            return ModuleInstallResult(
                success = false,
                log = "В assets не найден $EMBEDDED_ASSET_NAME. Экспорт невозможен.",
            )
        }
        val staged = stageAssetToCache()
            ?: return ModuleInstallResult(false, "Не удалось подготовить ZIP модуля во временном каталоге приложения.")

        val dst = PortGuardPaths.EXPORTED_MODULE_ZIP
        val script = buildString {
            append("mkdir -p ")
            append(rootShell.shellQuote(File(dst).parent ?: "/sdcard/Download"))
            append(" && cp ")
            append(rootShell.shellQuote(staged.absolutePath))
            append(' ')
            append(rootShell.shellQuote(dst))
            append(" && chmod 0644 ")
            append(rootShell.shellQuote(dst))
        }
        val result = rootShell.exec(script)
        val out = result.combinedOutput.ifBlank { "ZIP экспортирован в $dst" }
        return ModuleInstallResult(
            success = result.isSuccess,
            log = out,
            exportedPath = if (result.isSuccess) dst else null,
        )
    }

    suspend fun rebootDevice(): ModuleInstallResult {
        val result = rootShell.exec("svc power reboot || reboot")
        return ModuleInstallResult(
            success = result.isSuccess,
            log = result.combinedOutput.ifBlank { "Команда перезагрузки отправлена." },
        )
    }

    private suspend fun runInstallCommand(rawCommand: String): ModuleInstallResult {
        val stageLog = stageAssetToTmp()
        if (!stageLog.first) {
            return ModuleInstallResult(success = false, log = stageLog.second)
        }
        val installResult = rootShell.exec(rawCommand)
        val combined = listOf(stageLog.second, installResult.combinedOutput)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .ifBlank { "Команда установки выполнена." }
        return ModuleInstallResult(
            success = installResult.isSuccess,
            log = combined,
            requiresReboot = installResult.isSuccess,
        )
    }

    private suspend fun stageAssetToTmp(): Pair<Boolean, String> {
        val cacheFile = stageAssetToCache()
            ?: return false to "Не удалось извлечь ZIP модуля из assets."
        val command = "cp ${rootShell.shellQuote(cacheFile.absolutePath)} ${rootShell.shellQuote(PortGuardPaths.TMP_MODULE_ZIP)}"
        val result = rootShell.exec(command)
        return result.isSuccess to result.combinedOutput.ifBlank { "ZIP подготовлен в ${PortGuardPaths.TMP_MODULE_ZIP}" }
    }

    private suspend fun stageAssetToCache(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val outFile = File(context.cacheDir, EMBEDDED_ASSET_NAME)
            context.assets.open(EMBEDDED_ASSET_NAME).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        }.getOrNull()
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
}
