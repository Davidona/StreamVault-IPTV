#!/usr/bin/env sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
smoke_workflow="$repo_root/.github/workflows/platform-smoke.yml"
release_workflow="$repo_root/.github/workflows/release.yml"

[ -f "$smoke_workflow" ]
grep -F 'workflow_call:' "$smoke_workflow" >/dev/null
grep -F 'workflow_dispatch:' "$smoke_workflow" >/dev/null
grep -F 'push:' "$smoke_workflow" >/dev/null
grep -F -- '- develop' "$smoke_workflow" >/dev/null
grep -F 'uses: ./.github/workflows/platform-smoke.yml' "$release_workflow" >/dev/null
grep -F 'for test_script in tools/tests/*-test.sh' "$release_workflow" >/dev/null

if grep -F 'Precompile platform smoke APKs' "$smoke_workflow" "$release_workflow" >/dev/null; then
  printf 'platform smoke must build and install in one emulator-scoped invocation\n' >&2
  exit 1
fi

for api_level in 25 26 28 32 33 35 36; do
  grep -F -- "- api-level: $api_level" "$smoke_workflow" >/dev/null
done

runner_count=$(grep -F -c 'runner: ubuntu-latest' "$smoke_workflow")
if [ "$runner_count" -ne 7 ]; then
  printf 'all platform smoke jobs must run on KVM-capable Linux runners\n' >&2
  exit 1
fi

if grep -F 'runner: macos-' "$smoke_workflow" >/dev/null; then
  printf 'platform smoke must not use CPU-constrained macOS emulator runners\n' >&2
  exit 1
fi

grep -F 'name: Enable and verify KVM acceleration' "$smoke_workflow" >/dev/null
grep -F 'test -e /dev/kvm' "$smoke_workflow" >/dev/null
grep -F 'sudo chmod 0666 /dev/kvm' "$smoke_workflow" >/dev/null
grep -F 'test -r /dev/kvm' "$smoke_workflow" >/dev/null
grep -F 'test -w /dev/kvm' "$smoke_workflow" >/dev/null
grep -F 'disable-linux-hw-accel: false' "$smoke_workflow" >/dev/null

if grep -F 'udevadm trigger --name-match=kvm' "$smoke_workflow" >/dev/null; then
  printf 'platform smoke must configure /dev/kvm directly instead of relying on udev re-triggering\n' >&2
  exit 1
fi

grep -F 'platform-smoke-api-${{ matrix.api-level }}-diagnostics' "$smoke_workflow" >/dev/null
grep -F 'build/platform-smoke-diagnostics/' "$smoke_workflow" >/dev/null
grep -F 'app/build/outputs/androidTest-results/' "$smoke_workflow" >/dev/null
grep -F 'app/build/reports/androidTests/' "$smoke_workflow" >/dev/null
grep -F 'data/build/outputs/androidTest-results/' "$smoke_workflow" >/dev/null
grep -F 'data/build/reports/androidTests/' "$smoke_workflow" >/dev/null
grep -F 'emulator-boot-timeout: 900' "$smoke_workflow" >/dev/null
grep -F -- '-no-metrics' "$smoke_workflow" >/dev/null
grep -F 'adb_delay: -delay-adb' "$smoke_workflow" >/dev/null
grep -F 'matrix.adb_delay' "$smoke_workflow" >/dev/null

printf 'platform smoke workflow test passed\n'
