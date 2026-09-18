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
./gradlew --console=plain \
  :app:connectedDebugAndroidTest \
  "-PcompatApi=${api_level}" \
  "-PcompatAbi=${compat_abi}" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest \
  --no-daemon

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
