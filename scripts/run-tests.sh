#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cmake -S "$root/wsl-sender" -B "$root/build/wsl" -DCMAKE_BUILD_TYPE=Release
cmake --build "$root/build/wsl" --parallel 2
ctest --test-dir "$root/build/wsl" --output-on-failure
(cd "$root/upstream" && npm test)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
node "$root/tests/integration/create-fixture.mjs" "$fixture/100m.bin"
"$root/build/wsl/qsend" "$fixture/100m.bin" --attempt-factor 2.5 --export-frames "$fixture/100m.frames"
"$root/build/wsl/qsend" "$fixture/100m.bin" --passes 8 --export-frames "$fixture/default.frames"
export OPTIFERRY_DEFAULT_FRAMES="$fixture/default.frames"
export OPTIFERRY_FRAMES="$fixture/100m.frames"
bash "$root/scripts/build-android.sh"
