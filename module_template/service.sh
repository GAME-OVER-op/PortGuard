#!/system/bin/sh

while [[ "$(getprop sys.boot_completed)" != "1" ]]; do
  sleep 5
done

MODDIR="${0%/*}"
LOGDIR="/data/adb/modules/PortGuard/log"
LOGFILE="$LOGDIR/deamon.log"

mkdir -p "$LOGDIR"
setsid "$MODDIR/bin/portguard" >>"$LOGFILE" 2>&1 </dev/null &
