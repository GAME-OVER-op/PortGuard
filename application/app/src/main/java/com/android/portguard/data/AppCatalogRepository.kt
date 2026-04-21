package com.android.portguard.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

class AppCatalogRepository(
    private val context: Context,
) {
    suspend fun loadInstalledApps(): List<InstalledAppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }

        val packageInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())

        val applicationInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())

        val byPackage = linkedMapOf<String, InstalledAppEntry>()

        fun addFromInfo(pkg: String, info: ApplicationInfo?) {
            if (pkg.isBlank()) return
            val label = runCatching {
                when {
                    info != null -> pm.getApplicationLabel(info).toString()
                    else -> {
                        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getApplicationInfo(pkg, PackageManager.MATCH_ALL)
                        }
                        pm.getApplicationLabel(resolved).toString()
                    }
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            val flags = info?.flags ?: 0
            byPackage[pkg] = InstalledAppEntry(
                label = label,
                packageName = pkg,
                isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }

        packageInfos.forEach { pkgInfo: PackageInfo ->
            addFromInfo(pkgInfo.packageName.orEmpty(), pkgInfo.applicationInfo)
        }
        applicationInfos.forEach { appInfo: ApplicationInfo ->
            addFromInfo(appInfo.packageName.orEmpty(), appInfo)
        }

        byPackage.values
            .sortedWith(compareBy<InstalledAppEntry> { it.isSystem }.thenComparator { a, b ->
                val labelCompare = collator.compare(a.label, b.label)
                if (labelCompare != 0) labelCompare else a.packageName.compareTo(b.packageName)
            })
    }
}
