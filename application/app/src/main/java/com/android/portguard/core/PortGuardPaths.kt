package com.android.portguard.core

object PortGuardPaths {
    const val MODULE_ID = "PortGuard"
    const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    const val MODULE_UPDATE_DIR = "/data/adb/modules_update/$MODULE_ID"
    const val SETTINGS_DIR = "$MODULE_DIR/settings"
    const val STATE_DIR = "$SETTINGS_DIR/state"

    const val CONFIG_JSON = "$SETTINGS_DIR/config.json"
    const val TRUSTED_PACKAGES = "$SETTINGS_DIR/trusted_packages.txt"
    const val TRUSTED_UIDS = "$SETTINGS_DIR/trusted_uids.txt"
    const val KILL_EXCEPTIONS = "$SETTINGS_DIR/kill_exceptions.txt"
    const val SCAN_IGNORE_PACKAGES = "$SETTINGS_DIR/scan_ignore_packages.txt"

    const val STATUS_JSON = "$STATE_DIR/status.json"
    const val SUMMARY_JSON = "$STATE_DIR/summary.json"
    const val INCIDENTS_JSONL = "$STATE_DIR/incidents.jsonl"

    const val TMP_MODULE_ZIP = "/data/local/tmp/portguard_module.zip"
    const val EXPORTED_MODULE_ZIP = "/sdcard/Download/PortGuard_module.zip"
    const val BACKUP_DIR = "/sdcard/Download/PortGuard"
    const val SETTINGS_BACKUP_LATEST_JSON = "$BACKUP_DIR/PortGuard_settings_backup_latest.json"
    const val SETTINGS_BACKUP_TIMESTAMP_PREFIX = "$BACKUP_DIR/PortGuard_settings_backup_"
    const val MODULE_PROP = "$MODULE_DIR/module.prop"
    const val MODULE_UPDATE_PROP = "$MODULE_UPDATE_DIR/module.prop"
    const val TAB_LOG = "$MODULE_DIR/log/tab.log"

    val ALL_SETTINGS_FILES = listOf(
        CONFIG_JSON,
        TRUSTED_PACKAGES,
        TRUSTED_UIDS,
        KILL_EXCEPTIONS,
        SCAN_IGNORE_PACKAGES,
    )

    val ALL_STATE_FILES = listOf(
        STATUS_JSON,
        SUMMARY_JSON,
        INCIDENTS_JSONL,
    )
}
