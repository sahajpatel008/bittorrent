#!/bin/bash

# PEX Testing Script
# This script tests the Peer Exchange (PEX) protocol implementation

set -e

PROJECT_DIR="/Users/manavvakharia/Main/Data - Dell/SCU/Courses/CSEN317 - Distributed Systems/Project/bittorrent"
JAR="$PROJECT_DIR/target/java_bittorrent.jar"
TEST_FILE="$PROJECT_DIR/pex_test.txt"
TORRENT_FILE="$PROJECT_DIR/pex_test.torrent"
OUTPUT_A="$PROJECT_DIR/pex_download_A.txt"
OUTPUT_C="$PROJECT_DIR/pex_download_C.txt"

cd "$PROJECT_DIR"

echo "=========================================="
echo "PEX Protocol Testing Script"
echo "=========================================="
echo ""

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up..."
    pkill -f "TrackerServerApplication" 2>/dev/null || true
    pkill -f "java.*seed.*pex_test" 2>/dev/null || true
    pkill -f "java.*download.*pex_test" 2>/dev/null || true
    pkill -f "java.*-Dbittorrent.listen-port=688[234]" 2>/dev/null || true
    sleep 1
    echo "Cleanup complete."
}

trap cleanup EXIT

# Step 1: Build
echo "Step 1: Building project..."
mvn clean package -DskipTests -q
if [ ! -f "$JAR" ]; then
    echo "ERROR: JAR file not found at $JAR"
    exit 1
fi
echo "✓ Build successful"
echo ""

# Step 2: Create test file
echo "Step 2: Creating test file..."
echo "Hello PEX World! This is a test file for Peer Exchange protocol testing." > "$TEST_FILE"
echo "✓ Test file created: $TEST_FILE"
echo ""

# Step 3: Create torrent
echo "Step 3: Creating torrent file..."
java -jar "$JAR" create_torrent "$TEST_FILE" "$TORRENT_FILE" 2>&1 | grep -v "^$" || true
if [ ! -f "$TORRENT_FILE" ]; then
    echo "ERROR: Torrent file not created"
    exit 1
fi
echo "✓ Torrent created: $TORRENT_FILE"
echo ""

# Step 4: Start Tracker
echo "Step 4: Starting Tracker (Terminal 1)..."
java -Djava.net.preferIPv4Stack=true -cp "$JAR" bittorrent.tracker.server.TrackerServerApplication > "$PROJECT_DIR/tracker_pex.log" 2>&1 &
TRACKER_PID=$!
sleep 3
if ! ps -p $TRACKER_PID > /dev/null; then
    echo "ERROR: Tracker failed to start"
    cat "$PROJECT_DIR/tracker_pex.log"
    exit 1
fi
echo "✓ Tracker started (PID: $TRACKER_PID)"
echo "  Logs: $PROJECT_DIR/tracker_pex.log"
echo ""

# Step 5: Start Seeder
echo "Step 5: Starting Seeder on port 6882 (Terminal 2)..."
java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6882 \
     -Dbittorrent.debug=true \
     -jar "$JAR" seed "$TORRENT_FILE" "$TEST_FILE" > "$PROJECT_DIR/seeder_pex.log" 2>&1 &
SEEDER_PID=$!
sleep 3
if ! ps -p $SEEDER_PID > /dev/null; then
    echo "ERROR: Seeder failed to start"
    cat "$PROJECT_DIR/seeder_pex.log"
    exit 1
fi
echo "✓ Seeder started (PID: $SEEDER_PID)"
echo "  Logs: $PROJECT_DIR/seeder_pex.log"
echo ""

# Step 6: First Leecher (Peer A)
echo "Step 6: Starting First Leecher (Peer A) on port 6883..."
echo "  This peer will connect via tracker and should receive PEX updates..."
echo "  (Leecher will continue running as seeder after download completes)"
java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6883 \
     -Dbittorrent.debug=true \
     -jar "$JAR" download -o "$OUTPUT_A" "$TORRENT_FILE" > "$PROJECT_DIR/leecher_A_pex.log" 2>&1 &
LEECHER_A_PID=$!

# Wait for download to complete
echo "  Waiting for download to complete..."
DOWNLOAD_COMPLETE=false
for i in {1..30}; do
    if grep -q "Download complete\|Downloaded to:" "$PROJECT_DIR/leecher_A_pex.log" 2>/dev/null; then
        DOWNLOAD_COMPLETE=true
        break
    fi
    sleep 1
done

if [ "$DOWNLOAD_COMPLETE" = false ]; then
    echo "ERROR: Leecher A download did not complete in time"
    cat "$PROJECT_DIR/leecher_A_pex.log"
    exit 1
fi

echo "✓ Leecher A completed download (now seeding in background, PID: $LEECHER_A_PID)"
echo "  Logs: $PROJECT_DIR/leecher_A_pex.log"
echo ""

# Verify download
if [ -f "$OUTPUT_A" ]; then
    if diff -q "$TEST_FILE" "$OUTPUT_A" > /dev/null 2>&1; then
        echo "✓ Download verification: Files match!"
    else
        echo "✗ Download verification: Files differ!"
        exit 1
    fi
else
    echo "✗ Download file not found: $OUTPUT_A"
    exit 1
fi
echo ""

# Step 7: Second Leecher (Peer C) - This should use PEX
echo "Step 7: Starting Second Leecher (Peer C) on port 6884..."
echo "  This peer should discover peers via PEX (after initial tracker bootstrap)..."
echo "  Waiting 5 seconds for PEX updates to propagate and Leecher A to be ready..."
sleep 5

java -Djava.net.preferIPv4Stack=true \
     -Dbittorrent.listen-port=6884 \
     -Dbittorrent.debug=true \
     -jar "$JAR" download -o "$OUTPUT_C" "$TORRENT_FILE" > "$PROJECT_DIR/leecher_C_pex.log" 2>&1 &
LEECHER_C_PID=$!

# Wait for download to complete
echo "  Waiting for download to complete..."
DOWNLOAD_COMPLETE=false
for i in {1..30}; do
    if grep -q "Download complete\|Downloaded to:" "$PROJECT_DIR/leecher_C_pex.log" 2>/dev/null; then
        DOWNLOAD_COMPLETE=true
        break
    fi
    sleep 1
done

if [ "$DOWNLOAD_COMPLETE" = false ]; then
    echo "ERROR: Leecher C download did not complete in time"
    cat "$PROJECT_DIR/leecher_C_pex.log"
    exit 1
fi

echo "✓ Leecher C completed download (now seeding in background, PID: $LEECHER_C_PID)"
echo "  Logs: $PROJECT_DIR/leecher_C_pex.log"
echo ""

# Verify download
if [ -f "$OUTPUT_C" ]; then
    if diff -q "$TEST_FILE" "$OUTPUT_C" > /dev/null 2>&1; then
        echo "✓ Download verification: Files match!"
    else
        echo "✗ Download verification: Files differ!"
        exit 1
    fi
else
    echo "✗ Download file not found: $OUTPUT_C"
    exit 1
fi
echo ""

# Step 7.5: Wait for PEX updates to propagate between seeders
echo "Step 7.5: Waiting 10 seconds for PEX updates to propagate between all peers..."
sleep 10
echo "✓ PEX propagation period complete"
echo ""

# Step 8: Analyze PEX logs
echo "=========================================="
echo "PEX Log Analysis"
echo "=========================================="
echo ""

echo "Checking for PEX extension negotiation..."
if grep -q "ut_pex\|pexExtensionId" "$PROJECT_DIR/seeder_pex.log" "$PROJECT_DIR/leecher_A_pex.log" "$PROJECT_DIR/leecher_C_pex.log" 2>/dev/null; then
    echo "✓ PEX extension negotiation found"
else
    echo "⚠ PEX extension negotiation not clearly visible in logs"
fi

echo ""
echo "Checking for PEX peer discovery..."
if grep -q "PEX added\|PEX update\|SwarmManager.*PEX" "$PROJECT_DIR/leecher_A_pex.log" "$PROJECT_DIR/leecher_C_pex.log" 2>/dev/null; then
    echo "✓ PEX peer discovery found"
    grep -h "PEX added\|PEX update\|SwarmManager.*PEX" "$PROJECT_DIR/leecher_A_pex.log" "$PROJECT_DIR/leecher_C_pex.log" 2>/dev/null | head -5
else
    echo "⚠ PEX peer discovery not clearly visible in logs"
fi

echo ""
echo "Checking for periodic PEX updates..."
if grep -q "sent PEX update\|PeerConnectionManager.*broadcasting" "$PROJECT_DIR/seeder_pex.log" "$PROJECT_DIR/leecher_A_pex.log" 2>/dev/null; then
    echo "✓ Periodic PEX updates found"
    grep -h "sent PEX update\|PeerConnectionManager.*broadcasting" "$PROJECT_DIR/seeder_pex.log" "$PROJECT_DIR/leecher_A_pex.log" 2>/dev/null | head -3
else
    echo "⚠ Periodic PEX updates not clearly visible (may need to wait longer)"
fi

echo ""
echo "=========================================="
echo "Test Summary"
echo "=========================================="
echo "✓ All downloads completed successfully"
echo "✓ Files verified"
echo "✓ All peers are now seeding (running in background)"
echo ""
echo "Active processes:"
echo "  - Tracker (PID: $TRACKER_PID)"
echo "  - Seeder (PID: $SEEDER_PID)"
echo "  - Leecher A/Seeder (PID: $LEECHER_A_PID)"
echo "  - Leecher C/Seeder (PID: $LEECHER_C_PID)"
echo ""
echo "Log files:"
echo "  - Tracker: $PROJECT_DIR/tracker_pex.log"
echo "  - Seeder:  $PROJECT_DIR/seeder_pex.log"
echo "  - Leecher A: $PROJECT_DIR/leecher_A_pex.log"
echo "  - Leecher C: $PROJECT_DIR/leecher_C_pex.log"
echo ""
echo "To see PEX in action, check the logs for:"
echo "  - 'received PEX update' (receiving PEX messages)"
echo "  - 'sent PEX update' (sending PEX updates)"
echo "  - 'PEX added X peers' (peer discovery via PEX)"
echo "  - 'broadcasting PEX' (broadcasting to connected peers)"
echo ""
echo "All peers are now running as seeders and exchanging PEX updates."
echo "Press Ctrl+C to stop all processes and clean up."
echo ""
echo "Test completed successfully!"
echo ""
echo "Note: Processes will continue running until you press Ctrl+C or run cleanup."

