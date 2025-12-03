#!/bin/bash

# Script to start multiple peer instances
# Usage: ./start-peers.sh <number_of_peers>
# Example: ./start-peers.sh 5

set -e

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Get the script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Get the project root directory (parent of scripts directory)
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Function to check if a process is actually running
is_process_running() {
    local pid=$1
    if [ -z "$pid" ]; then
        return 1
    fi
    # Check if process exists and is running
    if kill -0 "$pid" 2>/dev/null; then
        return 0
    fi
    return 1
}

# Function to clean up stale PID files
cleanup_stale_pids() {
    local cleaned=0
    if [ ! -d "$PROJECT_ROOT/logs" ]; then
        return 0
    fi
    
    echo -e "${YELLOW}Checking for stale PID files...${NC}"
    for pid_file in "$PROJECT_ROOT/logs"/peer-*.pids; do
        if [ -f "$pid_file" ]; then
            local has_running=false
            # Check each PID in the file
            while IFS= read -r pid; do
                if is_process_running "$pid"; then
                    has_running=true
                    break
                fi
            done < "$pid_file"
            
            # If no processes are running, clean up the files
            if [ "$has_running" = false ]; then
                local peer_num=$(basename "$pid_file" | sed 's|peer-||' | sed 's|\.pids||')
                rm -f "$pid_file" "$PROJECT_ROOT/logs/peer-$peer_num.info"
                cleaned=$((cleaned + 1))
            fi
        fi
    done
    
    if [ $cleaned -gt 0 ]; then
        echo -e "${GREEN}Cleaned up $cleaned stale PID file(s)${NC}"
    else
        echo -e "${GREEN}No stale PID files found${NC}"
    fi
    echo ""
}

# Check if number of peers is provided
if [ $# -eq 0 ]; then
    echo -e "${RED}Error: Number of peers not specified${NC}"
    echo "Usage: $0 <number_of_peers>"
    echo "Example: $0 5"
    exit 1
fi

NUM_PEERS=$1

# Validate that NUM_PEERS is a positive integer
if ! [[ "$NUM_PEERS" =~ ^[1-9][0-9]*$ ]]; then
    echo -e "${RED}Error: Number of peers must be a positive integer${NC}"
    echo "Usage: $0 <number_of_peers>"
    echo "Example: $0 5"
    exit 1
fi

# Clean up stale PID files before starting
cleanup_stale_pids

echo -e "${GREEN}Starting $NUM_PEERS peer(s)...${NC}"
echo ""

# Start each peer
for i in $(seq 1 $NUM_PEERS); do
    echo -e "${YELLOW}[$i/$NUM_PEERS] Starting peer...${NC}"
    "$SCRIPT_DIR/start-peer.sh"
    
    # Add a small delay between starting peers to avoid port conflicts
    if [ $i -lt $NUM_PEERS ]; then
        sleep 2
    fi
done

echo ""
echo -e "${GREEN}Successfully started $NUM_PEERS peer(s)!${NC}"

