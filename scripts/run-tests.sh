#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export OPTIFERRY_BUILD_ROOT="${OPTIFERRY_BUILD_ROOT:-/mnt/f/optiferry-build}"
export OPTIFERRY_TEST_ROOT="${OPTIFERRY_TEST_ROOT:-/mnt/f/optiferry-tests}"
export ANDROID_HOME="${ANDROID_HOME:-/mnt/f/optiferry-storage/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
# Gradle changes cache directory modes; its user cache must stay on WSL's
# native filesystem rather than the Windows-mounted F drive.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
if [[ -z ${JAVA_HOME:-} && -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]]; then export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
python3 "$root/tests/test_cimbar_send.py"
cmake -S "$root/wsl-sender" -B "$OPTIFERRY_BUILD_ROOT/wsl" -DCMAKE_BUILD_TYPE=Release
cmake --build "$OPTIFERRY_BUILD_ROOT/wsl" --parallel 2
ctest --test-dir "$OPTIFERRY_BUILD_ROOT/wsl" --output-on-failure
(cd "$root/upstream" && npm test)
fixture=$(mktemp -d -p "$OPTIFERRY_TEST_ROOT")
trap 'rm -rf -- "$fixture"' EXIT
node "$root/tests/integration/create-fixture.mjs" "$fixture/100m.bin"
"$OPTIFERRY_BUILD_ROOT/wsl/qsend" "$fixture/100m.bin" --attempt-factor 2.5 --export-frames "$fixture/100m.frames"
"$OPTIFERRY_BUILD_ROOT/wsl/qsend" "$fixture/100m.bin" --passes 8 --export-frames "$fixture/default.frames"
export OPTIFERRY_DEFAULT_FRAMES="$fixture/default.frames"
export OPTIFERRY_FRAMES="$fixture/100m.frames"
bash "$root/scripts/build-android.sh"
