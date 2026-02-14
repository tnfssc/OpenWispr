#!/usr/bin/env bash
set -euo pipefail

echo "[check] swift-format lint"
./scripts/lint.sh

echo "[check] swift build"
swift build

echo "[check] self tests"
./scripts/test.sh
