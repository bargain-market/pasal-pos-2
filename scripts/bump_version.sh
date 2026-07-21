#!/bin/bash

# Ensure we are in the project root
cd "$(dirname "$0")/.."

PROP_FILE="src/main/resources/application.properties"
POM_FILE="pom.xml"

if [ ! -f "$PROP_FILE" ] || [ ! -f "$POM_FILE" ]; then
    echo "Error: Could not find application.properties or pom.xml"
    exit 1
fi

# Extract current version from pom.xml
CURRENT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

echo "Current version: $CURRENT_VERSION"

# Split version into components
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# Increment patch version
NEW_PATCH=$((PATCH + 1))
NEW_VERSION="${MAJOR}.${MINOR}.${NEW_PATCH}"

echo "New version: $NEW_VERSION"

# Update pom.xml
mvn versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false

# Update application.properties
# Use sed to replace the app.version line
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS requires an extension for -i
    sed -i '' "s/^app\.version=.*/app.version=$NEW_VERSION/" "$PROP_FILE"
else
    # Linux/others
    sed -i "s/^app\.version=.*/app.version=$NEW_VERSION/" "$PROP_FILE"
fi

echo "Updated version to $NEW_VERSION in pom.xml and application.properties"
