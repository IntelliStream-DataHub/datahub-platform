#!/usr/bin/env bash
# Build the RVM to glTF converter as one static binary.
#
# Clones the pinned upstream commit, applies the stdout patch next to this script, and links
# statically so the result can be dropped into a container with no runtime dependencies.
#
#   ./build.sh [output-dir]        default: ./build
set -euo pipefail

UPSTREAM=https://github.com/cdyk/rvmparser.git
# Pinned so a rebuild is reproducible. Bump deliberately, then re-check the patch applies.
COMMIT=0121659c8b5c56588af7c2f1209d6d8312dfd2b7

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
out=${1:-"$here/build"}
src="$out/rvmparser"

mkdir -p "$out"
if [ ! -d "$src/.git" ]; then
    git clone --quiet "$UPSTREAM" "$src"
fi
git -C "$src" fetch --quiet origin "$COMMIT" 2>/dev/null || git -C "$src" fetch --quiet origin
git -C "$src" checkout --quiet "$COMMIT"
git -C "$src" submodule update --quiet --init --recursive
git -C "$src" checkout --quiet -- src/

git -C "$src" apply "$here/0001-glb-to-stdout.patch"

make -C "$src/make" -j"$(nproc)" LDFLAGS="-static" >/dev/null
strip "$src/make/rvmparser"
install -m 0755 "$src/make/rvmparser" "$out/rvm-converter"

file "$out/rvm-converter" | grep -q "statically linked" || {
    echo "build.sh: expected a statically linked binary" >&2
    exit 1
}
echo "built $out/rvm-converter ($(stat -c%s "$out/rvm-converter") bytes, static)"
