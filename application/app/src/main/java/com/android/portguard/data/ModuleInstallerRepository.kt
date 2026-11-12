package com.android.portguard.data

import android.content.Context
import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    suspend fun exportModuleZipToInternalStorage(): ModuleInstallResult {
        if (!hasEmbeddedModuleAsset()) {
            return ModuleInstallResult(
                success = false,
                log = "В assets не найден $EMBEDDED_ASSET_NAME. Экспорт невозможен.",
            )
        }
        val staged = stageAssetToCache()
            ?: return ModuleInstallResult(false, "Не удалось подготовить ZIP модуля во временном каталоге приложения.")

        val dst = PortGuardPaths.EXPORTED_MODULE_ZIP
        val part = "$dst.part"
        val script = buildString {
            // The fallback path is intentionally fixed. Do not use SAF, Downloads,
            // or any alternate export directory: the user expects the module ZIP
            // directly in /storage/emulated/0/.
            append("rm -f ")
            append(rootShell.shellQuote(part))
            append(" && cp ")
            append(rootShell.shellQuote(staged.absolutePath))
            append(' ')
            append(rootShell.shellQuote(part))
            append(" && mv -f ")
            append(rootShell.shellQuote(part))
            append(' ')
            append(rootShell.shellQuote(dst))
        }
        val result = try {
            rootShell.exec(script)
        } finally {
            withContext(NonCancellable) {
                rootShell.exec("rm -f ${rootShell.shellQuote(part)}")
            }
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { staged.delete() }
            }
        }
        val out = result.combinedOutput.ifBlank { "ZIP сохранён в $dst" }
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
        val installResult = try {
            rootShell.exec(rawCommand)
        } finally {
            // The module archive can be large and contains an executable. Do
            // not leave it in /data/local/tmp after success, failure, or caller cancellation.
            withContext(NonCancellable) {
                rootShell.exec("rm -f ${rootShell.shellQuote(PortGuardPaths.TMP_MODULE_ZIP)}")
            }
        }
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
        val result = try {
            rootShell.exec(command)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { cacheFile.delete() }
            }
        }
        if (!result.isSuccess) {
            // cp may leave a truncated destination behind. Do not retain a large
            // or invalid module archive in /data/local/tmp after a staging error.
            withContext(NonCancellable) {
                rootShell.exec("rm -f ${rootShell.shellQuote(PortGuardPaths.TMP_MODULE_ZIP)}")
            }
        }
        return result.isSuccess to result.combinedOutput.ifBlank { "ZIP подготовлен в ${PortGuardPaths.TMP_MODULE_ZIP}" }
    }

    private suspend fun stageAssetToCache(): File? = withContext(Dispatchers.IO) {
        var outFile: File? = null
        try {
            // A unique cache file avoids an install/export race between two UI
            // actions using the same fixed module.zip staging path.
            val tempFile = File.createTempFile("portguard-module-", ".zip", context.cacheDir)
            outFile = tempFile
            context.assets.open(EMBEDDED_ASSET_NAME).use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            tempFile
        } catch (t: Throwable) {
            runCatching { outFile?.delete() }
            if (t is kotlinx.coroutines.CancellationException) throw t
            null
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
}
