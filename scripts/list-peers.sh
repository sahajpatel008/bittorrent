#!/bin/bash

# Script to list all running peer instances
# Shows peer number, ports, PIDs, and status

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Get the project root directory (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

# Function to check if a process is running
is_process_running() {
    local pid=$1
    kill -0 "$pid" 2>/dev/null
}

# Function to check if a port is in use
check_port() {
    local port=$1
    if command -v lsof > /dev/null 2>&1; then
        lsof -i :$port > /dev/null 2>&1
    elif command -v netstat > /dev/null 2>&1; then
        netstat -an | grep ":$port " > /dev/null 2>&1
    else
        false
    fi
}

echo -e "${BLUE}=== Running Peer Instances ===${NC}"
echo ""

# Check if logs directory exists
if [ ! -d "logs" ]; then
    echo -e "${YELLOW}No logs directory found. No peers have been started.${NC}"
    exit 0
fi

# Find all info files
found_any=false
for info_file in logs/peer-*.info; do
    if [ -f "$info_file" ]; then
        found_any=true
        # Extract peer number from filename
        peer_num=$(echo "$info_file" | sed 's|logs/peer-||' | sed 's|\.info||')
        
        # Source the info file to get variables
        source "$info_file"
        
        echo -e "${GREEN}Peer #$PEER_NUM${NC}"
        echo -e "  Frontend: http://localhost:$FRONTEND_PORT"
        echo -e "  Backend:  http://localhost:$BACKEND_PORT"
        echo -e "  Peer:     localhost:$PEER_PORT"
        
        # Check process status
        frontend_running=false
        backend_running=false
        
        if [ -n "$FRONTEND_PID" ] && is_process_running "$FRONTEND_PID"; then
            frontend_running=true
        elif check_port "$FRONTEND_PORT"; then
            frontend_running=true
        fi
        
        if [ -n "$BACKEND_PID" ] && is_process_running "$BACKEND_PID"; then
            backend_running=true
        elif check_port "$BACKEND_PORT"; then
            backend_running=true
        fi
        
        # Show status
        if [ "$frontend_running" = true ] && [ "$backend_running" = true ]; then
            echo -e "  Status:   ${GREEN}Running${NC}"
        elif [ "$frontend_running" = true ] || [ "$backend_running" = true ]; then
            echo -e "  Status:   ${YELLOW}Partially Running${NC}"
        else
            echo -e "  Status:   ${RED}Stopped${NC}"
        fi
        
        # Show PIDs if available
        if [ -n "$FRONTEND_PID" ] || [ -n "$BACKEND_PID" ]; then
            echo -e "  PIDs:     Frontend: ${FRONTEND_PID:-N/A}, Backend: ${BACKEND_PID:-N/A}"
        fi
        
        # Show log files
        echo -e "  Logs:     logs/peer-$PEER_NUM-frontend.log, logs/peer-$PEER_NUM-backend.log"
        echo ""
    fi
done

if [ "$found_any" = false ]; then
    echo -e "${YELLOW}No peer instances found.${NC}"
    echo -e "Start a peer with: ${GREEN}./scripts/start-peer.sh${NC}"
fi

