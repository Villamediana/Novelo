#!/usr/bin/env bash
# Builds the search-by-meaning check on the computer, against llama.cpp at the tag the app uses.
#
#   tools/fetch-llama.sh /tmp/x/llama.cpp
#   tools/sense-check/build.sh /tmp/x/llama.cpp /tmp/x/sense
#   /tmp/x/sense/sense-check embeddinggemma-2-UD-Q4_K_XL.gguf
set -euo pipefail
llama=${1:?uso: build.sh <llama.cpp> <pasta do build>}
out=${2:?uso: build.sh <llama.cpp> <pasta do build>}
here=$(cd "$(dirname "$0")" && pwd)
cmake -S "$here" -B "$out" -G Ninja -DCMAKE_BUILD_TYPE=Release -DLLAMA_DIR="$(cd "$llama" && pwd)" >/dev/null
cmake --build "$out" -j"$(nproc)" --target sense-check
echo "$out/sense-check"
