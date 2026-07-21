#!/bin/bash
set -e

# Configuration
APP_NAME="Pasal-POS"
MAIN_CLASS="com.pos.PosApplication"
VENDOR="YourCompany"

echo "Using Java Home: $JAVA_HOME"

# 1. Build using Maven
echo "Building project..."
mvn clean package -DskipTests

# 2. Determine Version
POM_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
# Use timestamp for patch version if snapshot
TIMESTAMP=$(date +%s)
CLEAN_VERSION=$(echo "$POM_VERSION" | cut -d. -f1-2).$TIMESTAMP
echo "Detected Version: $CLEAN_VERSION"

# 3. Find the packaged app jar
# Exclude Spring Boot's original thin jar so local installers use the runnable artifact.
MAIN_JAR=$(find target -name "pasal-pos-*-shaded.jar" | head -n 1)
if [ -z "$MAIN_JAR" ]; then
    MAIN_JAR=$(find target -name "pasal-pos-*.jar" -not -name "original-*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" | head -n 1)
fi

if [ -z "$MAIN_JAR" ]; then
    echo "Error: Could not find built JAR file."
    exit 1
fi
echo "Found JAR: $MAIN_JAR"

# 4. Prepare Dist
DIST_DIR="dist-local"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# 5. Run JPackage
echo "Creating Installer..."

# Detect OS
if [[ "$OSTYPE" == "darwin"* ]]; then
    TYPE="dmg"
    EXTRA_ARGS=(--mac-package-name "Pasal POS")
elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
    TYPE="exe"
     EXTRA_ARGS=(--win-menu --win-shortcut)
else
    TYPE="deb"
    EXTRA_ARGS=(--linux-menu-group "Utility")
fi

jpackage \
  --input target \
  --name "$APP_NAME" \
  --main-jar "$(basename "$MAIN_JAR")" \
  --main-class "$MAIN_CLASS" \
  --type "$TYPE" \
  --dest "$DIST_DIR" \
  --app-version "$CLEAN_VERSION" \
  --vendor "$VENDOR" \
  --java-options "-Xmx512m" \
  --java-options "-XX:MaxMetaspaceSize=192m" \
  --java-options "-XX:+UseG1GC" \
  --java-options "-XX:+UseStringDeduplication" \
  --java-options "-XX:MaxRAMPercentage=50.0" \
  "${EXTRA_ARGS[@]}"

echo "Installer created in $DIST_DIR"

# 6. Upload to GitHub Releases
ARTIFACT=$(find "$DIST_DIR" -type f \( -name "*.dmg" -o -name "*.exe" -o -name "*.deb" \) | head -n 1)

if [ -z "$ARTIFACT" ]; then
    echo "Error: Artifact not found."
    exit 1
fi

TAG_NAME="local-release-$TIMESTAMP"
echo "Creating GitHub Release $TAG_NAME..."

gh release create "$TAG_NAME" \
    "$ARTIFACT" \
    --title "Local Build $CLEAN_VERSION" \
    --notes "Deployed from local machine on $(date)" \
    --make-latest

echo "Done! Release created."
