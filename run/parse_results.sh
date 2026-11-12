#!/usr/bin/env bash
# =============================================================================
# AgroSence :: parse_results.sh
# Scans every results/agrosence_*.log produced by run.sh and condenses the
# key KPIs (execution time, loop latencies, total energy, cost, network
# usage) that iFogSim's Controller prints to stdout into one CSV file,
# results/summary.csv, so FOG_FREE_TIER vs CLOUD_CENTRIC runs can be
# compared side by side. Pure awk/bash -- no extra tooling required.
# =============================================================================
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR="$PROJECT_ROOT/results"
OUT="$RESULTS_DIR/summary.csv"

echo "log_file,execution_time_ms,total_energy,cost_of_execution,total_network_usage" > "$OUT"

shopt -s nullglob
for log in "$RESULTS_DIR"/agrosence_*.log; do
    base="$(basename "$log")"

    exec_time=$(grep -m1 "EXECUTION TIME" "$log" | awk -F':' '{gsub(/ /,"",$2); print $2}')

    total_energy=$(awk -F'=' '/Energy Consumed/ {gsub(/ /,"",$2); sum+=$2} END {printf "%.4f", sum}' "$log")

    cost=$(grep -m1 "Cost of execution" "$log" | awk -F'=' '{gsub(/ /,"",$2); print $2}')

    netusage=$(grep -m1 "Total network usage" "$log" | awk -F'=' '{gsub(/ /,"",$2); print $2}')

    echo "${base},${exec_time:-NA},${total_energy:-NA},${cost:-NA},${netusage:-NA}" >> "$OUT"
done

echo "[AgroSence] Summary written to: $OUT"
cat "$OUT"
