#!/bin/bash

# Script to start the tracker server
# Based on instructions from docs/TESTING.md

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Get the project root directory (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

# Tracker configuration
TRACKER_PORT=8080
TRACKER_LOG="logs/tracker.log"

# Create logs directory if it doesn't exist
mkdir -p logs

# Function to check if a port is in use
check_port() {
    local port=$1
    if command -v lsof > /dev/null 2>&1; then
        lsof -i :$port > /dev/null 2>&1
    elif command -v netstat > /dev/null 2>&1; then
        netstat -an | grep ":$port " > /dev/null 2>&1
    else
        # Fallback: try to connect to the port
        (echo > /dev/tcp/localhost/$port) > /dev/null 2>&1
    fi
}

# Check if tracker is already running
if check_port $TRACKER_PORT; then
    echo -e "${YELLOW}Port $TRACKER_PORT is already in use.${NC}"
    echo -e "${YELLOW}Tracker may already be running.${NC}"
    echo -e "Check with: ${GREEN}lsof -i :$TRACKER_PORT${NC}"
    echo -e "Or stop it with: ${GREEN}./scripts/stop-tracker.sh${NC}"
    exit 1
fi

# Check if tracker PID file exists
if [ -f "logs/tracker.pid" ]; then
    OLD_PID=$(cat logs/tracker.pid 2>/dev/null || echo "")
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        echo -e "${YELLOW}Tracker appears to be running (PID: $OLD_PID)${NC}"
        echo -e "Stop it first with: ${GREEN}./scripts/stop-tracker.sh${NC}"
        exit 1
    else
        # Clean up stale PID file
        rm -f logs/tracker.pid
    fi
fi

echo -e "${GREEN}Starting Tracker Server${NC}"
echo -e "  Port: http://localhost:$TRACKER_PORT"
echo -e "  Log:  $TRACKER_LOG"

# Start tracker
echo -e "${YELLOW}Starting tracker...${NC}"
cd "$SCRIPT_DIR"
mvn spring-boot:run \
  -Dspring-boot.run.main-class=bittorrent.tracker.server.TrackerServerApplication \
  -Dspring-boot.run.arguments="--server.port=$TRACKER_PORT" \
  > "$TRACKER_LOG" 2>&1 &
TRACKER_PID=$!

# Store PID for stopping
echo "$TRACKER_PID" > logs/tracker.pid

# Store tracker info
echo "TRACKER_PORT=$TRACKER_PORT" > logs/tracker.info
echo "TRACKER_PID=$TRACKER_PID" >> logs/tracker.info
echo "TRACKER_LOG=$TRACKER_LOG" >> logs/tracker.info

echo -e "${GREEN}Tracker started successfully!${NC}"
echo -e "  PID:  $TRACKER_PID"
echo -e "  Log:  $TRACKER_LOG"
echo -e ""
echo -e "To stop the tracker, run: ${YELLOW}./scripts/stop-tracker.sh${NC}"
echo -e "To view logs: ${YELLOW}tail -f $TRACKER_LOG${NC}"

