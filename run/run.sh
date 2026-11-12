#!/usr/bin/env bash
# =============================================================================
# AgroSence :: run.sh
# Usage:
#   ./run/run.sh            -> runs the FREE-TIER (fog-only / edge-ward) deployment
#   ./run/run.sh cloud       -> runs the CLOUD-CENTRIC deployment (for comparison)
#   ./run/run.sh advisory    -> runs the standalone AdvisoryLogic rule-engine demo
# =============================================================================
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$PROJECT_ROOT/bin"
LIB_DIR="$PROJECT_ROOT/lib"
RESULTS_DIR="$PROJECT_ROOT/results"

if [ ! -d "$BIN_DIR" ] || [ -z "$(ls -A "$BIN_DIR" 2>/dev/null)" ]; then
    echo "[AgroSence] No compiled classes found -- building first..."
    "$PROJECT_ROOT/run/build.sh"
fi

mkdir -p "$RESULTS_DIR"

CLASSPATH="$(find "$LIB_DIR" -name '*.jar' | tr '\n' ':')$BIN_DIR"

MODE="${1:-fog}"

if [ "$MODE" = "advisory" ]; then
    echo "[AgroSence] Running standalone AdvisoryLogic demo..."
    java -cp "$CLASSPATH" org.agrosence.advisory.AdvisoryLogic
    exit 0
fi

TS="$(date +%Y%m%d_%H%M%S)"
LOGFILE="$RESULTS_DIR/agrosence_${MODE}_${TS}.log"

echo "[AgroSence] Running simulation (mode=$MODE). Full console output is also saved to:"
echo "            $LOGFILE"
echo ""

java -cp "$CLASSPATH" org.agrosence.AgroSenceMain "$MODE" | tee "$LOGFILE"

echo ""
echo "[AgroSence] Done. Run './run/parse_results.sh' to summarize all saved logs into a CSV."
