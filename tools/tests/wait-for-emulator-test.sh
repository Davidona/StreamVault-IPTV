#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

counter_file="$temp_dir/adb-count"
printf '0\n' > "$counter_file"

cat > "$temp_dir/adb" <<'FAKE_ADB'
#!/usr/bin/env sh

set -eu

count_file=${FAKE_ADB_COUNT_FILE:?FAKE_ADB_COUNT_FILE is required}
command="$*"

case "$command" in
  "-s emulator-5554 get-state")
    count=$(cat "$count_file")
    count=$((count + 1))
    printf '%s\n' "$count" > "$count_file"
    if [ "$count" -lt 2 ]; then
      printf 'offline\n'
    else
      printf 'device\n'
    fi
    ;;
  *"shell getprop sys.boot_completed"*)
    printf '1\n'
    ;;
  *"shell pm path android"*)
    printf 'package:/system/framework/framework-res.apk\n'
    ;;
  *"shell sm list-volumes all"*)
    printf 'private mounted null\n'
    ;;
  *)
    printf 'unexpected adb invocation: %s\n' "$command" >&2
    exit 1
    ;;
esac
FAKE_ADB
chmod +x "$temp_dir/adb"

output=$(
  PATH="$temp_dir:$PATH" \
  FAKE_ADB_COUNT_FILE="$counter_file" \
  ANDROID_SERIAL=emulator-5554 \
  EMULATOR_READINESS_CHECKS=2 \
  EMULATOR_READINESS_ATTEMPTS=5 \
  EMULATOR_READINESS_INTERVAL=0 \
  "$repo_root/tools/wait-for-emulator.sh"
)

printf '%s\n' "$output" | grep -F 'Emulator emulator-5554 is ready' >/dev/null
[ "$(cat "$counter_file")" -ge 3 ]

printf 'wait-for-emulator test passed\n'
