#!/usr/bin/env sh

set -eu

api_level="${1:?API level is required}"
compat_abi="${2:-x86_64}"
artifact_dir="${PLATFORM_SMOKE_ARTIFACT_DIR:-build/platform-smoke-diagnostics}"
PLATFORM_SMOKE_ACTIVE_SUITE="startup"
PLATFORM_SMOKE_ARTIFACT_DIR="$artifact_dir"
export PLATFORM_SMOKE_ACTIVE_SUITE PLATFORM_SMOKE_ARTIFACT_DIR

cleanup_platform_smoke() {
  status=$?
  trap - EXIT

  if [ "$status" -ne 0 ]; then
    sh ./tools/capture-platform-smoke-diagnostics.sh || true
  fi

  exit "$status"
}

trap cleanup_platform_smoke EXIT

if [ "$api_level" = "35" ] || [ "$api_level" = "36" ]; then
  ./tools/wait-for-emulator.sh
fi

PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.app.compat.PlatformCompatibilityMatrixTest"
export PLATFORM_SMOKE_ACTIVE_SUITE
run_compatibility_suite() {
  if [ "$api_level" != "25" ]; then
    ./gradlew --console=plain \
      :app:connectedDebugAndroidTest \
      "-PcompatApi=${api_level}" \
      "-PcompatAbi=${compat_abi}" \
      -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest \
      --no-daemon
    return
  fi

  # API 25 occasionally loses the emulator's package-install stream after boot. Retry only
  # that infrastructure failure; real test failures still fail the smoke job immediately.
  attempt=1
  retry_log="${TMPDIR:-/tmp}/streamvault-platform-smoke-api-${api_level}.log"
  while [ "$attempt" -le 2 ]; do
    if ./gradlew --console=plain \
      :app:connectedDebugAndroidTest \
      "-PcompatApi=${api_level}" \
      "-PcompatAbi=${compat_abi}" \
      -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest \
      --no-daemon >"$retry_log" 2>&1; then
      cat "$retry_log"
      return
    fi

    cat "$retry_log"
    if [ "$attempt" -eq 2 ] || ! grep -Eq \
      'Failed to install (split )?APK|Failed to install-write all apks|device offline' "$retry_log"; then
      return 1
    fi

    adb wait-for-device || true
    adb uninstall com.streamvault.app.debug >/dev/null 2>&1 || true
    adb uninstall com.streamvault.app.debug.test >/dev/null 2>&1 || true
    attempt=$((attempt + 1))
  done
}

run_compatibility_suite

if [ "$api_level" = "35" ] || [ "$api_level" = "36" ]; then
  PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.app.service.DownloadForegroundServiceInstrumentationTest"
  export PLATFORM_SMOKE_ACTIVE_SUITE
  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceInstrumentationTest \
    --no-daemon

  PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.app.service.DownloadForegroundServiceQuotaInstrumentationTest"
  export PLATFORM_SMOKE_ACTIVE_SUITE
  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceQuotaInstrumentationTest \
    --no-daemon

  PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.app.service.DownloadForegroundServiceRecoveryInstrumentationTest"
  export PLATFORM_SMOKE_ACTIVE_SUITE
  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceRecoveryInstrumentationTest \
    --no-daemon

  PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.data.manager.recording.PlatformReleaseSafetyInstrumentationTest"
  export PLATFORM_SMOKE_ACTIVE_SUITE
  ./gradlew --console=plain \
    :data:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.manager.recording.PlatformReleaseSafetyInstrumentationTest \
    --no-daemon
fi

if [ "$api_level" = "25" ] || [ "$api_level" = "36" ]; then
  PLATFORM_SMOKE_ACTIVE_SUITE="com.streamvault.data.local.StreamVaultDatabaseMigrationTest"
  export PLATFORM_SMOKE_ACTIVE_SUITE
  ./gradlew --console=plain \
    :data:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest \
    --no-daemon
fi
