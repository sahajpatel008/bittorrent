#!/bin/bash

# Script to start a new peer instance with automatic port detection
# Starts frontend, backend, and peer server on next available ports

set -e

# Base ports
FRONTEND_BASE=5173
BACKEND_BASE=8081
PEER_BASE=6881

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

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

# Get the project root directory (parent of scripts directory)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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

# Function to check if any process from PID file is running
has_running_process() {
    local pid_file=$1
    if [ ! -f "$pid_file" ]; then
        return 1
    fi
    # Check each PID in the file
    while IFS= read -r pid; do
        if is_process_running "$pid"; then
            return 0
        fi
    done < "$pid_file"
    return 1
}

# Function to find next available peer number
find_next_peer() {
    local peer_num=1
    while true; do
        local frontend_port=$((FRONTEND_BASE + peer_num - 1))
        local backend_port=$((BACKEND_BASE + peer_num - 1))
        local peer_port=$((PEER_BASE + peer_num - 1))
        
        local port_in_use=false
        local process_running=false
        
        # Check if any of the ports are in use
        if check_port $frontend_port || check_port $backend_port || check_port $peer_port; then
            port_in_use=true
        fi
        
        # Check if PID file exists and has running processes
        local pid_file="$SCRIPT_DIR/logs/peer-$peer_num.pids"
        if [ -f "$pid_file" ]; then
            if has_running_process "$pid_file"; then
                process_running=true
            else
                # PID file exists but processes are dead - clean it up
                rm -f "$pid_file" "$SCRIPT_DIR/logs/peer-$peer_num.info"
            fi
        fi
        
        # If ports are in use or processes are running, try next peer number
        if [ "$port_in_use" = true ] || [ "$process_running" = true ]; then
            peer_num=$((peer_num + 1))
        else
            break
        fi
    done
    echo $peer_num
}

cd "$SCRIPT_DIR"

# Create logs directory if it doesn't exist
mkdir -p logs

# Find next available peer number
PEER_NUM=$(find_next_peer)
FRONTEND_PORT=$((FRONTEND_BASE + PEER_NUM - 1))
BACKEND_PORT=$((BACKEND_BASE + PEER_NUM - 1))
PEER_PORT=$((PEER_BASE + PEER_NUM - 1))

echo -e "${GREEN}Starting Peer #$PEER_NUM${NC}"
echo -e "  Frontend: http://localhost:$FRONTEND_PORT"
echo -e "  Backend:  http://localhost:$BACKEND_PORT"
echo -e "  Peer:     localhost:$PEER_PORT"

# Start backend
echo -e "${YELLOW}Starting backend...${NC}"
cd "$SCRIPT_DIR"
mvn spring-boot:run \
  -Dspring-boot.run.main-class=bittorrent.BitTorrentApplication \
  -Dspring-boot.run.arguments="--server.port=$BACKEND_PORT --bittorrent.listen-port=$PEER_PORT" \
  > logs/peer-$PEER_NUM-backend.log 2>&1 &
BACKEND_PID=$!

# Wait a moment for backend to start
sleep 3

# Start frontend
echo -e "${YELLOW}Starting frontend...${NC}"
FRONTEND_DIR="$SCRIPT_DIR/react-frontend"
if [ ! -d "$FRONTEND_DIR" ]; then
    echo -e "${RED}react-frontend directory not found at $FRONTEND_DIR${NC}"
    exit 1
fi

cd "$FRONTEND_DIR" || { echo -e "${RED}Failed to change to react-frontend directory${NC}"; exit 1; }

# Check if node_modules exists and has required modules, if not install/reinstall dependencies
NEEDS_INSTALL=false
if [ ! -d "node_modules" ]; then
    NEEDS_INSTALL=true
    echo -e "${YELLOW}node_modules directory not found${NC}"
elif [ ! -f "node_modules/.bin/vite" ]; then
    NEEDS_INSTALL=true
    echo -e "${YELLOW}vite binary not found${NC}"
elif [ ! -d "node_modules/@rollup/rollup-darwin-arm64" ]; then
    NEEDS_INSTALL=true
    echo -e "${YELLOW}rollup native module (darwin-arm64) not found${NC}"
fi

if [ "$NEEDS_INSTALL" = true ]; then
    echo -e "${YELLOW}Installing/reinstalling frontend dependencies...${NC}"
    # Remove package-lock.json and node_modules if they exist to fix rollup native module issues
    [ -f "package-lock.json" ] && rm -f package-lock.json
    [ -d "node_modules" ] && rm -rf node_modules
    echo -e "${YELLOW}Running npm install...${NC}"
    npm install > "$SCRIPT_DIR/logs/peer-$PEER_NUM-frontend-install.log" 2>&1
    INSTALL_EXIT_CODE=$?
    if [ $INSTALL_EXIT_CODE -ne 0 ]; then
        echo -e "${RED}Failed to install frontend dependencies (exit code: $INSTALL_EXIT_CODE)${NC}"
        echo -e "${RED}Check logs/peer-$PEER_NUM-frontend-install.log for details${NC}"
        exit 1
    fi
    # Verify rollup module was installed
    if [ ! -d "node_modules/@rollup/rollup-darwin-arm64" ]; then
        echo -e "${RED}Warning: rollup native module still missing after install${NC}"
        echo -e "${YELLOW}Trying to install rollup explicitly...${NC}"
        npm install @rollup/rollup-darwin-arm64 --save-optional >> "$SCRIPT_DIR/logs/peer-$PEER_NUM-frontend-install.log" 2>&1
    fi
    echo -e "${GREEN}Frontend dependencies installed successfully${NC}"
fi

# PORT sets the frontend port
# VITE_BACKEND_PORT sets the backend port for the proxy target
# VITE_API_BASE is not set, so frontend will use "/api" which goes through the proxy
# Run from react-frontend directory
cd "$FRONTEND_DIR"
PORT=$FRONTEND_PORT \
VITE_BACKEND_PORT=$BACKEND_PORT \
npm run dev -- --port $FRONTEND_PORT > "$SCRIPT_DIR/logs/peer-$PEER_NUM-frontend.log" 2>&1 &
FRONTEND_PID=$!

# Store PIDs for stopping
echo "$BACKEND_PID" > "$SCRIPT_DIR/logs/peer-$PEER_NUM.pids"
echo "$FRONTEND_PID" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.pids"

# Store peer info
echo "PEER_NUM=$PEER_NUM" > "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"
echo "FRONTEND_PORT=$FRONTEND_PORT" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"
echo "BACKEND_PORT=$BACKEND_PORT" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"
echo "PEER_PORT=$PEER_PORT" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"
echo "FRONTEND_PID=$FRONTEND_PID" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"
echo "BACKEND_PID=$BACKEND_PID" >> "$SCRIPT_DIR/logs/peer-$PEER_NUM.info"

echo -e "${GREEN}Peer #$PEER_NUM started successfully!${NC}"
echo -e "  Frontend PID: $FRONTEND_PID"
echo -e "  Backend PID:  $BACKEND_PID"
echo -e "  Logs: logs/peer-$PEER_NUM-*.log"
echo -e ""
echo -e "To stop this peer, run: ${YELLOW}./stop-peer.sh $PEER_NUM${NC}"

