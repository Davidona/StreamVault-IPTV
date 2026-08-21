#!/usr/bin/env sh

set -eu

api_level="${1:?API level is required}"
compat_abi="${2:-x86_64}"

./gradlew --console=plain \
  :app:connectedDebugAndroidTest \
  "-PcompatApi=${api_level}" \
  "-PcompatAbi=${compat_abi}" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest \
  --no-daemon

cleanup_api_35_or_36() {
  status=$?
  adb shell device_config delete activity_manager data_sync_fgs_timeout_duration >/dev/null 2>&1 || true
  adb shell am compat disable FGS_INTRODUCE_TIME_LIMITS com.streamvault.app.debug >/dev/null 2>&1 || true
  exit "$status"
}

if [ "$api_level" = "35" ] || [ "$api_level" = "36" ]; then
  trap cleanup_api_35_or_36 EXIT
  adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.streamvault.app.debug
  adb shell device_config put activity_manager data_sync_fgs_timeout_duration 5000

  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceInstrumentationTest \
    --no-daemon

  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceQuotaInstrumentationTest \
    --no-daemon

  ./gradlew --console=plain \
    :app:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.service.DownloadForegroundServiceRecoveryInstrumentationTest \
    --no-daemon

  ./gradlew --console=plain \
    :data:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.manager.recording.PlatformReleaseSafetyInstrumentationTest \
    --no-daemon
fi

if [ "$api_level" = "25" ] || [ "$api_level" = "36" ]; then
  ./gradlew --console=plain \
    :data:connectedDebugAndroidTest \
    "-PcompatAbi=${compat_abi}" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest \
    --no-daemon
fi
