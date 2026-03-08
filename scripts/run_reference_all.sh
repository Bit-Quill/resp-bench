#!/bin/bash
# =============================================================================
# run_reference_all.sh — Run all reference Java benchmarks
#
# Generates workload files for each client count from the single-client
# template, then runs every driver × client-count combination 5 times,
# flushing the server between runs.  Results are appended to NDJSON files:
#   <output-dir>/<N>-clients/<driver>.ndjson
#
# Prerequisites:
#   - pwd is the repo root
#   - Valkey/Redis server running at the given host on port 6379
#   - Java 21+, Maven, redis-cli in PATH
#
# Usage:
#   ./scripts/run_reference_all.sh <output-dir> <server-host>
#
# Examples:
#   ./scripts/run_reference_all.sh results/my-machine/reference localhost
#   ./scripts/run_reference_all.sh results/my-machine/reference 192.168.1.50
# =============================================================================
set -euo pipefail

OUTPUT_DIR=$1
SERVER_HOST=$2
SERVER="${SERVER_HOST}:6379"

ITER_CNT=10
CLIENT_CNTS=(1 2 4 8 16 32 64 128)
#CLIENT_CNTS=(16 32 64)
DRIVERS=(jedis valkey-glide lettuce redisson spring-data-redis-jedis spring-data-redis-lettuce spring-data-valkey-glide spring-data-valkey-jedis spring-data-valkey-lettuce)
#DRIVERS=(valkey-glide spring-data-valkey-glide)

TEMPLATE=configs/workloads/reference/basic-standalone-single-client.json
WORKLOAD_DIR=configs/workloads/reference

# Drivers that have a pool_size config that should match the client count
POOLED_DRIVERS=(spring-data-valkey-jedis spring-data-valkey-glide spring-data-redis-jedis spring-data-valkey-lettuce spring-data-redis-lettuce)

# Generate workload files for each client count from the single-client template
for client_cnt in "${CLIENT_CNTS[@]}"; do
    WORKLOAD_FILE="${WORKLOAD_DIR}/basic-standalone-${client_cnt}-clients.json"
    cp "$TEMPLATE" "$WORKLOAD_FILE"
    sed -i "s/\"connections\": 1/\"connections\": ${client_cnt}/g" "$WORKLOAD_FILE"
done

# Generate per-client-count driver configs for pooled drivers (pool_size = client_cnt)
TMPDIR_DRIVERS=$(mktemp -d)
trap "rm -rf $TMPDIR_DRIVERS" EXIT
for client_cnt in "${CLIENT_CNTS[@]}"; do
    for pooled_driver in "${POOLED_DRIVERS[@]}"; do
        SRC="configs/drivers/reference/${pooled_driver}.json"
        if [ -f "$SRC" ]; then
            mkdir -p "${TMPDIR_DRIVERS}/${client_cnt}"
            sed "s/\"pool_size\": [0-9]*/\"pool_size\": ${client_cnt}/" "$SRC" \
                > "${TMPDIR_DRIVERS}/${client_cnt}/${pooled_driver}.json"
        fi
    done
done

# Run benchmarks
for i in $(seq 1 "$ITER_CNT"); do
    for client_cnt in "${CLIENT_CNTS[@]}"; do
        WORKLOAD_FILE="configs/workloads/reference/basic-standalone-${client_cnt}-clients.json"

        for driver in "${DRIVERS[@]}"; do
            echo "=== iter=$i  clients=$client_cnt  driver=$driver ==="
            redis-cli -h "$SERVER_HOST" -p 6379 flushall
            mkdir -p "$OUTPUT_DIR/${client_cnt}-clients"

            # Use per-client-count driver config if available (pooled drivers), else original
            DRIVER_FILE="${TMPDIR_DRIVERS}/${client_cnt}/${driver}.json"
            if [ ! -f "$DRIVER_FILE" ]; then
                DRIVER_FILE="configs/drivers/reference/${driver}.json"
            fi

            make java-run \
                SERVER="$SERVER" \
                DRIVER="$DRIVER_FILE" \
                WORKLOAD="$WORKLOAD_FILE" \
                METRICS_OUTPUT="$OUTPUT_DIR/${client_cnt}-clients/${driver}.ndjson"
        done
    done
done
