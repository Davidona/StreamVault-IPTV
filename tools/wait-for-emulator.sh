#!/usr/bin/env sh

set -eu

device_serial="${ANDROID_SERIAL:-emulator-5554}"
max_attempts="${EMULATOR_READINESS_ATTEMPTS:-90}"
required_stable_checks="${EMULATOR_READINESS_CHECKS:-3}"
interval_seconds="${EMULATOR_READINESS_INTERVAL:-2}"
stable_checks=0

is_storage_ready() {
  case "$1" in
    *" mounted "*) return 0 ;;
    *) return 1 ;;
  esac
}

printf 'Waiting for emulator %s package and storage services...\n' "$device_serial"

attempt=1
while [ "$attempt" -le "$max_attempts" ]; do
  state=$(adb -s "$device_serial" get-state 2>/dev/null | tr -d '\r' || true)
  boot_completed=$(adb -s "$device_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  package_path=$(adb -s "$device_serial" shell pm path android 2>/dev/null | tr -d '\r' || true)
  storage_volumes=$(adb -s "$device_serial" shell sm list-volumes all 2>/dev/null | tr -d '\r' || true)

  if [ "$state" = "device" ] && \
    [ "$boot_completed" = "1" ] && \
    case "$package_path" in package:*) true ;; *) false ;; esac && \
    is_storage_ready "$storage_volumes"; then
    stable_checks=$((stable_checks + 1))
    if [ "$stable_checks" -ge "$required_stable_checks" ]; then
      printf 'Emulator %s is ready (%s consecutive checks).\n' "$device_serial" "$stable_checks"
      exit 0
    fi
  else
    stable_checks=0
  fi

  attempt=$((attempt + 1))
  sleep "$interval_seconds"
done

printf 'Emulator %s did not become ready after %s checks.\n' "$device_serial" "$max_attempts" >&2
printf '  adb state: %s\n' "$state" >&2
printf '  boot completed: %s\n' "$boot_completed" >&2
printf '  package path: %s\n' "$package_path" >&2
printf '  storage volumes:\n%s\n' "$storage_volumes" >&2
exit 1
