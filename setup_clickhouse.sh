#!/bin/bash
# Script to create ClickHouse schema on remote server
# Usage: ./setup_clickhouse.sh [user@host]

HOST=${1:-"ubuntu@13.48.166.136"}

echo "Deploying ClickHouse schema to $HOST..."

# Find ClickHouse container ID (assuming image name contains 'clickhouse')
CONTAINER_ID=$(ssh $HOST "docker ps -qf 'ancestor=clickhouse/clickhouse-server' | head -n1")
if [ -z "$CONTAINER_ID" ]; then
    # Try finding by name if ancestor lookup fails
    CONTAINER_ID=$(ssh $HOST "docker ps -q | head -n1") # Fallback to first container or improved logic needed
    # Better: try specific common names
    CONTAINER_ID=$(ssh $HOST "docker ps --format '{{.Names}}' | grep clickhouse | head -n1")
fi

if [ -z "$CONTAINER_ID" ]; then
    echo "Error: Could not find running ClickHouse container on $HOST"
    exit 1
fi

echo "Found ClickHouse container: $CONTAINER_ID"

# Execute SQL inside the container with multiquery support
ssh $HOST "docker exec -i $CONTAINER_ID clickhouse-client --multiquery --query=\"$(cat clickhouse_schema.sql)\""
