#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

workspace="$temp_dir/workspace"
artifact_dir="$workspace/platform-smoke-artifacts"
adb_calls="$temp_dir/adb-calls"
mkdir -p "$workspace/tools" "$artifact_dir"
cp "$repo_root/tools/run-platform-smoke.sh" "$workspace/tools/run-platform-smoke.sh"
if [ -f "$repo_root/tools/capture-platform-smoke-diagnostics.sh" ]; then
  cp "$repo_root/tools/capture-platform-smoke-diagnostics.sh" "$workspace/tools/capture-platform-smoke-diagnostics.sh"
fi

cat > "$workspace/gradlew" <<'FAKE_GRADLEW'
#!/usr/bin/env sh
printf 'intentional instrumentation failure\n' >&2
exit 23
FAKE_GRADLEW
chmod +x "$workspace/gradlew" "$workspace/tools/run-platform-smoke.sh"

cat > "$temp_dir/adb" <<'FAKE_ADB'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" >> "${FAKE_ADB_CALLS:?}"
case "$*" in
  *"getprop"*) printf '[ro.build.version.sdk]: [33]\n' ;;
  *"pm list instrumentation"*) printf 'instrumentation:fixture/runner (target=fixture)\n' ;;
  *"logcat"*) printf 'fixture crash evidence\n' ;;
  *"dumpsys dropbox"*) printf 'fixture dropbox evidence\n' ;;
  *"dumpsys package"*) printf 'fixture package evidence\n' ;;
  *"devices"*) printf 'List of devices attached\nemulator-5554 device\n' ;;
esac
FAKE_ADB
chmod +x "$temp_dir/adb"

set +e
(
  cd "$workspace"
  PATH="$temp_dir:$PATH" \
  FAKE_ADB_CALLS="$adb_calls" \
  PLATFORM_SMOKE_ARTIFACT_DIR="$artifact_dir" \
  ./tools/run-platform-smoke.sh 33 x86_64
)
status=$?
set -e

[ "$status" -eq 23 ]
grep -F 'PlatformCompatibilityMatrixTest' "$artifact_dir/active-suite.txt" >/dev/null
grep -F 'logcat -b crash -d' "$adb_calls" >/dev/null
grep -F 'dumpsys dropbox --print data_app_crash' "$adb_calls" >/dev/null
grep -F 'fixture crash evidence' "$artifact_dir/crash-buffer.log" >/dev/null

printf 'platform smoke diagnostics test passed\n'
