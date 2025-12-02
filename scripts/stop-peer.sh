#!/bin/bash

# Script to stop a peer instance
# Usage: ./stop-peer.sh <peer_number> or ./stop-peer.sh all

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Get the project root directory (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

# Function to stop a peer by number
stop_peer() {
    local peer_num=$1
    local pid_file="logs/peer-$peer_num.pids"
    local info_file="logs/peer-$peer_num.info"
    
    if [ ! -f "$pid_file" ]; then
        echo -e "${RED}Peer #$peer_num not found (no PID file)${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Stopping Peer #$peer_num...${NC}"
    
    # Read PIDs from file
    local pids=$(cat "$pid_file" 2>/dev/null || echo "")
    
    if [ -z "$pids" ]; then
        echo -e "${RED}No PIDs found for Peer #$peer_num${NC}"
        rm -f "$pid_file" "$info_file"
        return 1
    fi
    
    # Kill each process
    local killed=0
    for pid in $pids; do
        if kill -0 "$pid" 2>/dev/null; then
            echo -e "  Stopping process $pid..."
            # Try graceful shutdown first
            kill -TERM "$pid" 2>/dev/null || true
            sleep 2
            # Force kill if still running
            if kill -0 "$pid" 2>/dev/null; then
                kill -KILL "$pid" 2>/dev/null || true
            fi
            killed=1
        else
            echo -e "  Process $pid already stopped"
        fi
    done
    
    # Also try to kill by port (in case PID file is stale)
    if [ -f "$info_file" ]; then
        source "$info_file"
        # Kill processes using the ports
        for port in $FRONTEND_PORT $BACKEND_PORT $PEER_PORT; do
            if command -v lsof > /dev/null 2>&1; then
                lsof -ti :$port | xargs kill -TERM 2>/dev/null || true
                sleep 1
                lsof -ti :$port | xargs kill -KILL 2>/dev/null || true
            fi
        done
    fi
    
    # Clean up files
    rm -f "$pid_file" "$info_file"
    
    if [ $killed -eq 1 ]; then
        echo -e "${GREEN}Peer #$peer_num stopped${NC}"
    else
        echo -e "${YELLOW}Peer #$peer_num was not running${NC}"
    fi
}

# Main logic
if [ $# -eq 0 ]; then
    echo -e "${RED}Usage: $0 <peer_number> | all${NC}"
    echo -e "  Example: $0 1"
    echo -e "  Example: $0 all"
    exit 1
fi

if [ "$1" = "all" ]; then
    echo -e "${YELLOW}Stopping all peers...${NC}"
    # Find all PID files
    for pid_file in logs/peer-*.pids; do
        if [ -f "$pid_file" ]; then
            # Extract peer number from filename
            peer_num=$(echo "$pid_file" | sed 's|logs/peer-||' | sed 's|\.pids||')
            stop_peer "$peer_num"
        fi
    done
    echo -e "${GREEN}All peers stopped${NC}"
else
    peer_num=$1
    if ! [[ "$peer_num" =~ ^[0-9]+$ ]]; then
        echo -e "${RED}Invalid peer number: $peer_num${NC}"
        exit 1
    fi
    stop_peer "$peer_num"
fi

