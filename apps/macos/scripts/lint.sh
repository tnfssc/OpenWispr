#!/usr/bin/env bash
set -euo pipefail

swift format lint --strict --recursive Sources SelfTests
