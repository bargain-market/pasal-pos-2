#!/bin/bash

# Pasal POS Database Clear Utility Script
# This script completely clears the POS system database by:
# 1. Dropping all tables and indexes
# 2. Shutting down the database gracefully
# 3. Deleting all database files

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}Pasal POS Database Clear Utility${NC}"
echo -e "${YELLOW}========================================${NC}"
echo ""

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}Error: Maven is not installed or not in PATH${NC}"
    echo "Please install Maven to use this script."
    exit 1
fi

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo -e "${RED}Error: Java is not installed or not in PATH${NC}"
    echo "Please install Java to use this script."
    exit 1
fi

# Confirm deletion
echo -e "${RED}WARNING: This will DELETE ALL DATA from the POS database!${NC}"
echo ""
echo "This will:"
echo "  - Drop all database tables and indexes"
echo "  - Delete all database files (.mv.db, .trace.db, .lock.db)"
echo "  - Completely reset the local database"
echo ""
read -p "Are you sure you want to continue? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo -e "${YELLOW}Database clearing cancelled.${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}Checking if POS application is running...${NC}"

# Check if POS application is running
POS_RUNNING=0
if pgrep -f "PosApplication" > /dev/null 2>&1; then
    POS_RUNNING=1
    echo -e "${RED}✗ POS application is RUNNING${NC}"
    echo ""
    echo "The POS application must be CLOSED before deleting the database."
    echo "The application is currently using the database, which prevents deletion."
    echo ""
    echo "Please:"
    echo "  1. Close the POS application window"
    echo "  2. Stop the Maven process (Ctrl+C in the terminal running 'mvn javafx:run')"
    echo "  3. Wait 3 seconds"
    echo "  4. Run this script again"
    exit 1
else
    echo -e "${GREEN}✓ POS application is not running${NC}"
fi

# Check if H2 web console is running
if lsof -i :8082 > /dev/null 2>&1; then
    echo -e "${YELLOW}⚠ H2 web console is running on port 8082${NC}"
    echo "Closing H2 web console..."
    pkill -f "h2.*8082" 2>/dev/null
    pkill -f "org.h2.tools.Server" 2>/dev/null
    sleep 2
fi

echo ""
echo -e "${YELLOW}NOTE ABOUT AUTO-SYNC:${NC}"
echo "After deletion, when you start the POS application, it will automatically"
echo "sync data from the backend server. This means the database will be"
echo "repopulated with data from your backend. This is NORMAL and EXPECTED behavior."
echo ""
echo "The database IS being deleted - it's just being repopulated from the backend."
echo ""
read -p "Continue with deletion? (yes/no): " continue_delete

if [ "$continue_delete" != "yes" ]; then
    echo -e "${YELLOW}Deletion cancelled.${NC}"
    exit 0
fi

echo ""
echo -e "${YELLOW}Clearing database...${NC}"
echo ""

# Determine database location
DB_DIR="$HOME/Library/Application Support/Pasal POS"
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    DB_DIR="$HOME/.pos-system"
elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
    DB_DIR="$APPDATA/Pasal POS"
fi

DB_BASE="$DB_DIR/posdb"
DB_FILES=("$DB_BASE.mv.db" "$DB_BASE.trace.db" "$DB_BASE.lock.db")

echo "Database location: $DB_DIR"
echo ""

# Check if database files exist
FILES_EXIST=0
for file in "${DB_FILES[@]}"; do
    if [ -f "$file" ]; then
        FILES_EXIST=1
        echo "Found: $(basename "$file") ($(du -h "$file" | cut -f1))"
    fi
done

if [ $FILES_EXIST -eq 0 ]; then
    echo -e "${YELLOW}No database files found. Database may already be cleared.${NC}"
    exit 0
fi

echo ""
echo "Deleting database files directly..."
DELETED_COUNT=0
FAILED_COUNT=0

# Try multiple times to delete files (in case they're being recreated)
for attempt in 1 2 3; do
    if [ $attempt -gt 1 ]; then
        echo "  Retry attempt $attempt..."
        sleep 1
    fi
    
    for file in "${DB_FILES[@]}"; do
        if [ -f "$file" ]; then
            echo -n "  Deleting $(basename "$file")... "
            rm -f "$file" 2>/dev/null
            sleep 0.2  # Brief pause to ensure file system updates
            if [ ! -f "$file" ]; then
                if [ $attempt -eq 1 ]; then
                    echo -e "${GREEN}✓${NC}"
                    DELETED_COUNT=$((DELETED_COUNT + 1))
                fi
            else
                if [ $attempt -eq 3 ]; then
                    echo -e "${RED}✗ FAILED${NC}"
                    FAILED_COUNT=$((FAILED_COUNT + 1))
                fi
            fi
        fi
    done
    
    # Check if all files are deleted
    REMAINING_CHECK=$(find "$DB_DIR" -name "posdb.*" 2>/dev/null | wc -l | tr -d ' ')
    if [ "$REMAINING_CHECK" -eq 0 ]; then
        break
    fi
done

# Verify deletion
REMAINING=$(find "$DB_DIR" -name "posdb.*" 2>/dev/null | wc -l | tr -d ' ')

if [ "$REMAINING" -eq 0 ]; then
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Database cleared successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "✓ All database files have been deleted."
    echo ""
    echo -e "${YELLOW}IMPORTANT:${NC}"
    echo "When you start the POS application, it will automatically sync data"
    echo "from the backend server. This is normal behavior for an offline-first system."
    echo ""
    echo "The database is now empty, but will be repopulated when the app starts."
    echo "This ensures the POS system always has the latest data from your backend."
    echo ""
    echo "To verify the database is empty before sync:"
    echo "  1. Check H2 console at http://localhost:8082 (after app starts)"
    echo "  2. Or check the application logs - it will show 'local products: 0'"
    exit 0
fi

# If some files failed, try Java method as fallback
if [ $FAILED_COUNT -gt 0 ]; then
    echo ""
    echo -e "${YELLOW}Some files could not be deleted directly. Trying Java method...${NC}"
    echo ""
    
    # Run the database clear utility via Maven
    mvn exec:java \
        -Dexec.mainClass="com.pos.util.DatabaseClearUtility" \
        -Dexec.args="--force" \
        -q
    
    EXIT_CODE=$?
    
    # Verify deletion after Java method
    REMAINING_AFTER_JAVA=$(find "$DB_DIR" -name "posdb.*" 2>/dev/null | wc -l | tr -d ' ')
    
    if [ "$REMAINING_AFTER_JAVA" -eq 0 ]; then
        echo ""
        echo -e "${GREEN}========================================${NC}"
        echo -e "${GREEN}Database cleared successfully!${NC}"
        echo -e "${GREEN}========================================${NC}"
        echo ""
        echo "All database files have been deleted."
        echo "The database will be recreated with a fresh schema on next application startup."
        exit 0
    else
        echo ""
        echo -e "${RED}========================================${NC}"
        echo -e "${RED}ERROR: Failed to delete all database files${NC}"
        echo -e "${RED}========================================${NC}"
        echo ""
        echo "$REMAINING_AFTER_JAVA file(s) could not be deleted."
        echo ""
        echo "If files are locked, try:"
        echo "  1. Close all running instances of the POS application"
        echo "  2. Close H2 web console in browser (if open)"
        echo "  3. Wait a few seconds"
        echo "  4. Run: ./delete-db-direct.sh"
        echo ""
        echo "Or manually delete the files:"
        for file in "${DB_FILES[@]}"; do
            if [ -f "$file" ]; then
                echo "  rm -f \"$file\""
            fi
        done
        exit 1
    fi
fi

