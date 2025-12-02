#!/bin/bash

# Script to stop the tracker server

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Get the project root directory (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

PID_FILE="logs/tracker.pid"
INFO_FILE="logs/tracker.info"

if [ ! -f "$PID_FILE" ]; then
    echo -e "${RED}Tracker not found (no PID file)${NC}"
    exit 1
fi

TRACKER_PID=$(cat "$PID_FILE" 2>/dev/null || echo "")

if [ -z "$TRACKER_PID" ]; then
    echo -e "${RED}No PID found in $PID_FILE${NC}"
    rm -f "$PID_FILE" "$INFO_FILE"
    exit 1
fi

echo -e "${YELLOW}Stopping Tracker Server...${NC}"

# Check if process is running
if kill -0 "$TRACKER_PID" 2>/dev/null; then
    echo -e "  Stopping process $TRACKER_PID..."
    # Try graceful shutdown first
    kill -TERM "$TRACKER_PID" 2>/dev/null || true
    sleep 2
    # Force kill if still running
    if kill -0 "$TRACKER_PID" 2>/dev/null; then
        kill -KILL "$TRACKER_PID" 2>/dev/null || true
    fi
    echo -e "${GREEN}Tracker stopped${NC}"
else
    echo -e "${YELLOW}Tracker process $TRACKER_PID was not running${NC}"
fi

# Also try to kill by port (in case PID file is stale)
if [ -f "$INFO_FILE" ]; then
    source "$INFO_FILE"
    if [ -n "$TRACKER_PORT" ]; then
        if command -v lsof > /dev/null 2>&1; then
            lsof -ti :$TRACKER_PORT | xargs kill -TERM 2>/dev/null || true
            sleep 1
            lsof -ti :$TRACKER_PORT | xargs kill -KILL 2>/dev/null || true
        fi
    fi
fi

# Clean up files
rm -f "$PID_FILE" "$INFO_FILE"

echo -e "${GREEN}Tracker cleanup complete${NC}"

