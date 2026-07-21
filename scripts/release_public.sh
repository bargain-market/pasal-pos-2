#!/bin/bash
set -e

# Configuration
PUBLIC_REPO="bargain-market/desktop-app-releases"
DIST_DIR="dist-local"

echo "Checking GitHub CLI authentication..."
if ! gh auth status --hostname github.com 2>&1 | grep -q "workflow"; then
    echo "ERROR: 'workflow' scope is missing in 'gh' auth."
    echo "Please run: gh auth refresh -h github.com -s workflow"
    exit 1
fi

# 1. Find the latest artifact
ARTIFACT=$(find "$DIST_DIR" -type f \( -name "*.dmg" -o -name "*.exe" -o -name "*.deb" \) -mmin -60 | head -n 1)

if [ -z "$ARTIFACT" ]; then
    echo "No recent artifact found in $DIST_DIR (looking for files created in the last 60 minutes)."
    echo "Please run bash scripts/deploy_local.sh first."
    exit 1
fi

echo "Found artifact: $ARTIFACT"

# 2. Extract Version from JAR/Filename or ask user
# For now, we'll try to extract it from the filename which deploy_local.sh sets
# Filename format: e.g. dist-local/POS-System-1.0.32.dmg (Wait, deploy_local uses CLEAN_VERSION which is Major.Minor.Timestamp)
# Actually, let's just ask the user or use the JAR version if possible.
POM_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
TAG_NAME="v$POM_VERSION"

read -p "Create release for tag $TAG_NAME? (y/n): " confirm
if [[ $confirm != "y" ]]; then
    read -p "Enter tag name (e.g. v1.0.32): " TAG_NAME
fi

TITLE="Release $TAG_NAME"

echo "Releasing $TAG_NAME to $PUBLIC_REPO..."

# 3. Create or Upload to Public Repo
if gh release view "$TAG_NAME" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
    echo "Release $TAG_NAME exists. Uploading asset..."
    gh release upload "$TAG_NAME" "$ARTIFACT" --repo "$PUBLIC_REPO" --clobber
else
    echo "Creating release $TAG_NAME..."
    gh release create "$TAG_NAME" \
        "$ARTIFACT" \
        --repo "$PUBLIC_REPO" \
        --title "$TITLE" \
        --notes "Release $TAG_NAME (Manual build from local)" \
        --generate-notes \
        --latest
fi

KEEP_RELEASES="${KEEP_RELEASES:-5}" PUBLIC_REPO="$PUBLIC_REPO" bash scripts/prune_public_releases.sh

echo "Done! Release created in $PUBLIC_REPO."
