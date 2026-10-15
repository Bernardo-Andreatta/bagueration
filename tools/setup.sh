#!/usr/bin/env bash
# Downloads what's too big for git (speech engine + voice model) and bakes the voice blends.
# Needs: curl, tar, python3. Creates a throwaway venv for numpy + onnx.
set -euo pipefail
cd "$(dirname "$0")/.."
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
ASSETS=app/src/main/assets/kokoro
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p app/libs "$ASSETS"
if [ ! -f app/libs/sherpa-onnx-1.13.8.aar ]; then
  echo "Downloading sherpa-onnx 1.13.8 (speech engine)…"
  curl -fL -o app/libs/sherpa-onnx-1.13.8.aar "$REL/v1.13.8/sherpa-onnx-1.13.8.aar"
fi

echo "Downloading Kokoro v1.0 (≈330 MB)…"
curl -fL "$REL/tts-models/kokoro-multi-lang-v1_0.tar.bz2" | tar xj -C "$WORK"
SRC="$WORK/kokoro-multi-lang-v1_0"
cp -R "$SRC/espeak-ng-data" "$SRC/tokens.txt" "$SRC/lexicon-us-en.txt" "$SRC/LICENSE" "$ASSETS/"

echo "Baking voice blends…"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" -q install numpy onnx
"$WORK/venv/bin/python" tools/bake_blends.py "$SRC" "$ASSETS"
echo "Ready. Build with: ./gradlew assemblePersonalDebug"
