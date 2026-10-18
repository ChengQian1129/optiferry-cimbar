#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if ! grep -qi microsoft /proc/sys/kernel/osrelease; then
    echo 'ERROR: run this installer inside WSL2 Ubuntu.' >&2
    exit 1
fi
if [[ ! -d /mnt/wslg ]]; then
    echo 'ERROR: WSLg GUI support is required for the Cimbar sender.' >&2
    exit 1
fi

if [[ $(id -u) -eq 0 ]]; then
    admin=()
else
    admin=(sudo)
fi

"${admin[@]}" apt-get update
"${admin[@]}" apt-get install -y \
    build-essential cmake git pkg-config python3 \
    libopencv-dev libglfw3-dev libgles2-mesa-dev libgl1-mesa-dev

base_dir="${CIMBAR_HOME:-$HOME/.cache/optiferry-cimbar}"
source_dir="${CIMBAR_SOURCE_DIR:-$base_dir/libcimbar}"
build_dir="${CIMBAR_BUILD_DIR:-$base_dir/build}"
sender_install="${CIMBAR_INSTALL_DIR:-$HOME/.local/libexec/optiferry-cimbar}"
ref="${CIMBAR_REF:-bfb0c8e471820ae493cd3694ea6bed5d5ac06c37}"
url='https://github.com/sz3/libcimbar.git'
patch="$root/patches/libcimbar-wslg-opengl.patch"
cadence_patch="$root/patches/libcimbar-multi-input-cadence.patch"

if [[ ! -e "$source_dir" ]]; then
    mkdir -p "$(dirname -- "$source_dir")"
    git init "$source_dir" >/dev/null
    git -C "$source_dir" config core.autocrlf false
    git -C "$source_dir" remote add origin "$url"
    git -C "$source_dir" fetch --depth 1 origin "$ref"
    git -C "$source_dir" checkout --detach FETCH_HEAD >/dev/null
elif [[ ! -d "$source_dir/.git" ]]; then
    echo "ERROR: source directory exists but is not a git checkout: $source_dir" >&2
    exit 1
else
    git -C "$source_dir" config core.autocrlf false
    if ! git -C "$source_dir" cat-file -e "$ref^{commit}" 2>/dev/null; then
        git -C "$source_dir" fetch --depth 1 origin "$ref"
    fi
    git -C "$source_dir" checkout --detach "$ref" >/dev/null
fi

if grep -qF 'glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)' \
    "$source_dir/src/lib/gui/window_glfw.h" && \
    grep -qF 'out vec4 fragColor' "$source_dir/src/lib/gui/gl_2d_display.h"; then
    echo 'WSLg OpenGL compatibility patch already present.'
else
    git -C "$source_dir" apply --check "$patch"
    git -C "$source_dir" apply "$patch"
fi

if grep -qF 'cimbare_next_block_id' "$source_dir/src/lib/cimbar_js/cimbar_js.h" && \
    grep -qF 'wrap the 7-bit stream ID' "$source_dir/src/lib/cimbar_js/cimbar_js.cpp" && \
    grep -qF 'requestedFrameLimit' "$source_dir/src/exe/cimbar_send/send.cpp" && \
    grep -qF 'blocksPerFrame == 0' "$source_dir/src/exe/cimbar_send/send.cpp" && \
    grep -qF 'nextBlockId' "$source_dir/src/exe/cimbar_send/send.cpp" && \
    grep -qF 'const int encodeId = infiles.size() > 1' "$source_dir/src/exe/cimbar_send/send.cpp"; then
    echo 'Multi-input stream cadence patch already present.'
else
    # Older working trees used blocks_required() as a frame count. Remove only
    # those three managed files before applying the corrected frame-budget patch.
    if grep -qF 'cimbare_blocks_required' "$source_dir/src/lib/cimbar_js/cimbar_js.h"; then
        git -C "$source_dir" checkout -- \
            src/lib/cimbar_js/cimbar_js.h \
            src/lib/cimbar_js/cimbar_js.cpp \
            src/exe/cimbar_send/send.cpp
    fi
    git -C "$source_dir" apply --check "$cadence_patch"
    git -C "$source_dir" apply "$cadence_patch"
fi

cmake -S "$source_dir" -B "$build_dir" \
    -DCMAKE_BUILD_TYPE=Release -DDISABLE_TESTS=ON
cmake --build "$build_dir" --target cimbar_send --parallel "${JOBS:-2}"

sender=$(find "$build_dir" -type f -name cimbar_send -perm -u+x -print -quit)
if [[ -z "$sender" ]]; then
    echo "ERROR: build finished but cimbar_send was not found under $build_dir" >&2
    exit 1
fi

install -Dm755 "$sender" "$sender_install/cimbar_send"
sha256sum "$sender_install/cimbar_send" >"$sender_install/cimbar_send.sha256"
install -Dm755 "$root/scripts/cimbar-send.py" "$HOME/.local/bin/cimbar-send"

for profile in "$HOME/.profile" "$HOME/.bashrc"; do
    if [[ ! -f "$profile" ]]; then
        touch "$profile"
    fi
    if ! grep -qF '# OptiFerry Cimbar commands' "$profile"; then
        cat >>"$profile" <<'PROFILE'

# OptiFerry Cimbar commands
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) export PATH="$HOME/.local/bin:$PATH" ;; esac
PROFILE
    fi
done

"$HOME/.local/bin/cimbar-send" --help >/dev/null
printf '\nInstalled Cimbar sender: %s\n' "$sender_install/cimbar_send"
printf 'Installed command: %s/.local/bin/cimbar-send\n' "$HOME"
printf 'Install dist/OptiFerry-debug.apk, select 彩码 Cimbar, then run: cimbar-send /path/to/large-file\n'
