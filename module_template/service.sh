#!/system/bin/sh

# PortGuard daemon supervisor. Keep this script lightweight: Magisk/KernelSU/APatch
# starts it once per boot and it keeps the Rust daemon alive if it exits.

while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  sleep 5
done

MODDIR="${0%/*}"
BINARY="$MODDIR/bin/portguard"
LOGDIR="$MODDIR/log"
LOGFILE="$LOGDIR/daemon.log"
OLD_LOGFILE="$LOGDIR/deamon.log"
SUPERVISOR_PIDFILE="$LOGDIR/supervisor.pid"
PIDFILE="$LOGDIR/portguard.pid"
LOGPIPE="$LOGDIR/daemon.pipe"
MAX_LOG_BYTES=1048576
RESTART_DELAY=5
MAX_RESTART_DELAY=60
STABLE_RUNTIME=300
CHILD_PID=""
SINK_PID=""

mkdir -p "$LOGDIR"

# Migrate the old misspelled log once, without losing existing diagnostics.
if [ -f "$OLD_LOGFILE" ] && [ ! -f "$LOGFILE" ]; then
  mv -f "$OLD_LOGFILE" "$LOGFILE" 2>/dev/null || true
fi

rotate_log() {
  [ -f "$LOGFILE" ] || return 0
  SIZE="$(wc -c < "$LOGFILE" 2>/dev/null | tr -d ' ')"
  case "$SIZE" in
    ''|*[!0-9]*) return 0 ;;
  esac
  if [ "$SIZE" -ge "$MAX_LOG_BYTES" ]; then
    rm -f "$LOGFILE.1"
    mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null || : > "$LOGFILE"
  fi
}

log_sink() {
  if [ -f "$LOGFILE" ]; then
    CURRENT_SIZE="$(wc -c < "$LOGFILE" 2>/dev/null | tr -d ' ')"
  else
    CURRENT_SIZE=0
  fi
  case "$CURRENT_SIZE" in
    ''|*[!0-9]*) CURRENT_SIZE=0 ;;
  esac

  while IFS= read -r LINE || [ -n "$LINE" ]; do
    if [ "$CURRENT_SIZE" -ge "$MAX_LOG_BYTES" ]; then
      rm -f "$LOGFILE.1"
      mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null || : > "$LOGFILE"
      CURRENT_SIZE=0
    fi
    printf '%s\n' "$LINE" >> "$LOGFILE"
    CURRENT_SIZE=$((CURRENT_SIZE + ${#LINE} + 1))
  done < "$LOGPIPE"
}

cleanup() {
  if [ -n "$CHILD_PID" ] && kill -0 "$CHILD_PID" 2>/dev/null; then
    # A normal supervisor shutdown must not leave an untracked daemon behind.
    # Only escalate while the PID still belongs to this module binary so PID
    # reuse cannot terminate an unrelated process.
    if is_portguard_daemon_pid "$CHILD_PID"; then
      kill "$CHILD_PID" 2>/dev/null || true
      CLEANUP_WAIT=0
      while kill -0 "$CHILD_PID" 2>/dev/null && is_portguard_daemon_pid "$CHILD_PID" && [ "$CLEANUP_WAIT" -lt 5 ]; do
        sleep 1
        CLEANUP_WAIT=$((CLEANUP_WAIT + 1))
      done
      if kill -0 "$CHILD_PID" 2>/dev/null && is_portguard_daemon_pid "$CHILD_PID"; then
        kill -9 "$CHILD_PID" 2>/dev/null || true
      fi
    fi
  fi
  if [ -n "$SINK_PID" ] && kill -0 "$SINK_PID" 2>/dev/null; then
    kill "$SINK_PID" 2>/dev/null || true
  fi
  rm -f "$PIDFILE" "$SUPERVISOR_PIDFILE" "$LOGPIPE"
}

shutdown() {
  cleanup
  trap - INT TERM HUP EXIT
  exit 0
}

is_portguard_supervisor_pid() {
  PID="$1"
  [ -r "/proc/$PID/cmdline" ] || return 1
  CMDLINE="$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)"
  case "$CMDLINE" in
    *"$MODDIR/service.sh"*) return 0 ;;
  esac
  return 1
}

is_portguard_daemon_pid() {
  PID="$1"
  [ -r "/proc/$PID/cmdline" ] || return 1
  CMDLINE="$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)"
  case "$CMDLINE" in
    *"$BINARY"*) return 0 ;;
  esac
  return 1
}

stop_orphan_daemon() {
  [ -f "$PIDFILE" ] || return 0
  ORPHAN_PID="$(cat "$PIDFILE" 2>/dev/null)"
  case "$ORPHAN_PID" in
    ''|*[!0-9]*) rm -f "$PIDFILE"; return 0 ;;
  esac

  if kill -0 "$ORPHAN_PID" 2>/dev/null && is_portguard_daemon_pid "$ORPHAN_PID"; then
    printf '[%s] [supervisor] stopping orphan daemon pid=%s before restart\n' \
      "$(date +%s 2>/dev/null)" "$ORPHAN_PID" >> "$LOGFILE"
    kill "$ORPHAN_PID" 2>/dev/null || true
    WAIT_COUNT=0
    while kill -0 "$ORPHAN_PID" 2>/dev/null && is_portguard_daemon_pid "$ORPHAN_PID" && [ "$WAIT_COUNT" -lt 10 ]; do
      sleep 1
      WAIT_COUNT=$((WAIT_COUNT + 1))
    done
    if kill -0 "$ORPHAN_PID" 2>/dev/null && is_portguard_daemon_pid "$ORPHAN_PID"; then
      kill -9 "$ORPHAN_PID" 2>/dev/null || true
    fi
  fi
  rm -f "$PIDFILE"
}

# Avoid two supervisors after an accidental second service invocation. A PID
# file may survive an unclean reboot while Linux later reuses that numeric PID
# for an unrelated process, so kill -0 alone is not a valid ownership check.
if [ -f "$SUPERVISOR_PIDFILE" ]; then
  OLD_PID="$(cat "$SUPERVISOR_PIDFILE" 2>/dev/null)"
  case "$OLD_PID" in
    ''|*[!0-9]*) rm -f "$SUPERVISOR_PIDFILE" ;;
    *)
      if kill -0 "$OLD_PID" 2>/dev/null && is_portguard_supervisor_pid "$OLD_PID"; then
        exit 0
      fi
      rm -f "$SUPERVISOR_PIDFILE"
      ;;
  esac
fi
printf '%s\n' "$$" > "$SUPERVISOR_PIDFILE"
trap shutdown INT TERM HUP
trap cleanup EXIT

# If a previous supervisor was killed without running its trap, its child can
# survive and keep modifying the same firewall. Never run two PortGuard daemons.
stop_orphan_daemon

while true; do
  # Do not respawn while the module is being disabled/removed.
  [ -e "$MODDIR/disable" ] && break
  [ -e "$MODDIR/remove" ] && break

  if [ ! -x "$BINARY" ]; then
    rotate_log
    printf '[%s] [supervisor] daemon binary missing or not executable: %s\n' "$(date +%s)" "$BINARY" >> "$LOGFILE"
    sleep "$MAX_RESTART_DELAY"
    continue
  fi

  rotate_log
  rm -f "$LOGPIPE"
  if ! mkfifo "$LOGPIPE" 2>/dev/null; then
    printf '[%s] [supervisor] cannot create log pipe: %s\n' "$(date +%s)" "$LOGPIPE" >> "$LOGFILE"
    sleep "$MAX_RESTART_DELAY"
    continue
  fi
  log_sink &
  SINK_PID=$!

  STARTED_AT="$(date +%s 2>/dev/null)"
  "$BINARY" > "$LOGPIPE" 2>&1 </dev/null &
  CHILD_PID=$!
  printf '%s\n' "$CHILD_PID" > "$PIDFILE"

  wait "$CHILD_PID"
  EXIT_CODE=$?
  ENDED_AT="$(date +%s 2>/dev/null)"
  rm -f "$PIDFILE"
  CHILD_PID=""
  wait "$SINK_PID" 2>/dev/null || true
  SINK_PID=""
  rm -f "$LOGPIPE"

  case "$STARTED_AT" in
    ''|*[!0-9]*) RUNTIME=0 ;;
    *)
      case "$ENDED_AT" in
        ''|*[!0-9]*) RUNTIME=0 ;;
        *) RUNTIME=$((ENDED_AT - STARTED_AT)) ;;
      esac
      ;;
  esac

  rotate_log
  printf '[%s] [supervisor] daemon exited rc=%s runtime=%ss; restart in %ss\n' \
    "${ENDED_AT:-0}" "$EXIT_CODE" "$RUNTIME" "$RESTART_DELAY" >> "$LOGFILE"

  sleep "$RESTART_DELAY"
  if [ "$RUNTIME" -ge "$STABLE_RUNTIME" ]; then
    RESTART_DELAY=5
  else
    RESTART_DELAY=$((RESTART_DELAY * 2))
    [ "$RESTART_DELAY" -gt "$MAX_RESTART_DELAY" ] && RESTART_DELAY="$MAX_RESTART_DELAY"
  fi
done

cleanup
trap - INT TERM HUP EXIT
exit 0
