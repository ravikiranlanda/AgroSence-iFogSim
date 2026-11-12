#!/usr/bin/env bash
# =============================================================================
# AgroSence :: build.sh
# Plain javac build -- NO Maven, NO Ant, as required by the project brief.
# All third-party jars required by iFogSim/CloudSim are already vendored in
# lib/ (no internet access / package manager needed to build or run).
# =============================================================================
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$PROJECT_ROOT/src"
BIN_DIR="$PROJECT_ROOT/bin"
LIB_DIR="$PROJECT_ROOT/lib"

echo "[AgroSence] Building with plain javac (no Maven/Ant)..."
mkdir -p "$BIN_DIR"

CLASSPATH="$(find "$LIB_DIR" -name '*.jar' | tr '\n' ':')"

find "$SRC_DIR" -name "*.java" > "$PROJECT_ROOT/.sources.txt"

javac -nowarn -Xlint:none -encoding UTF-8 \
      -cp "$CLASSPATH" \
      -d "$BIN_DIR" \
      @"$PROJECT_ROOT/.sources.txt"

rm -f "$PROJECT_ROOT/.sources.txt"

echo "[AgroSence] Build complete. Classes written to: $BIN_DIR"
