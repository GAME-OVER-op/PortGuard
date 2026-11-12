package com.android.portguard.data

import android.content.Context
import android.content.pm.ApplicationInfo
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

        val applicationInfos = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())

        val byPackage = linkedMapOf<String, InstalledAppEntry>()
        applicationInfos.forEach { appInfo: ApplicationInfo ->
            val pkg = appInfo.packageName.orEmpty()
            if (pkg.isBlank()) return@forEach
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            byPackage[pkg] = InstalledAppEntry(
                label = label,
                packageName = pkg,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }

        byPackage.values
            .sortedWith(compareBy<InstalledAppEntry> { it.isSystem }.thenComparator { a, b ->
                val labelCompare = collator.compare(a.label, b.label)
                if (labelCompare != 0) labelCompare else a.packageName.compareTo(b.packageName)
            })
    }
}
