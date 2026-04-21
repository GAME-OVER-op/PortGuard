package com.android.portguard.data

enum class PortGuardPreset(val key: String) {
    BALANCED("balanced"),
    STRICT("strict"),
    AGGRESSIVE("aggressive");
}

data class SettingsBackupBundle(
    val exportedAt: String,
    val configRaw: String,
    val trustedPackages: List<String>,
    val trustedUids: List<String>,
    val killExceptions: List<String>,
    val scanIgnorePackages: List<String>,
)

data class SettingsActionResult(
    val success: Boolean,
    val message: String,
    val path: String? = null,
)
