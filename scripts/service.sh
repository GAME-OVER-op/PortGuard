#!/system/bin/sh
# Пример запуска в стиле root-модуля PortGuard.
# Настройки подхватываются автоматически только из:
# /data/adb/modules/PortGuard/settings/

BIN="/data/adb/modules/PortGuard/bin/portguard"

exec "$BIN"
