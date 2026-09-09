#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if ! grep -qi microsoft /proc/sys/kernel/osrelease; then echo 'ERROR: run this installer inside WSL2 Ubuntu.' >&2; exit 1; fi
if [[ ! -d /mnt/wslg ]]; then echo 'ERROR: WSLg GUI support is required.' >&2; exit 1; fi
if [[ $(id -u) -eq 0 ]]; then admin=(); else admin=(sudo); fi
"${admin[@]}" apt-get update
"${admin[@]}" apt-get install -y build-essential cmake pkg-config libsdl2-dev libsdl2-ttf-dev libssl-dev fonts-dejavu-core
cmake -S "$root/wsl-sender" -B "$root/build/wsl" -DCMAKE_BUILD_TYPE=Release
cmake --build "$root/build/wsl" --parallel 2
ctest --test-dir "$root/build/wsl" --output-on-failure
install -Dm755 "$root/build/wsl/qsend" "$HOME/.local/bin/qsend"
"$HOME/.local/bin/qsend" --selftest
printf '\nInstalled: %s/.local/bin/qsend\n' "$HOME"
# Make the documented qsend command available in newly opened WSL shells.
for profile in "$HOME/.profile" "$HOME/.bashrc"; do
    if ! grep -qF '# OptiFerry local commands' "$profile" 2>/dev/null; then
        cat >> "$profile" <<'PROFILE'

# OptiFerry local commands
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) export PATH="$HOME/.local/bin:$PATH" ;; esac
PROFILE
    fi
done
if [[ -f "$HOME/.bash_profile" ]] && ! grep -qF '# OptiFerry local commands' "$HOME/.bash_profile"; then
    cat >> "$HOME/.bash_profile" <<'PROFILE'

# OptiFerry local commands
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) export PATH="$HOME/.local/bin:$PATH" ;; esac
PROFILE
fi
printf 'Open a new WSL terminal, then run: qsend /path/to/file\n'