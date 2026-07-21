#!/bin/bash
set -euo pipefail

PUBLIC_REPO="${PUBLIC_REPO:-bargain-market/desktop-app-releases}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"

if ! [[ "$KEEP_RELEASES" =~ ^[0-9]+$ ]] || [ "$KEEP_RELEASES" -lt 1 ]; then
    echo "ERROR: KEEP_RELEASES must be a positive integer. Got: $KEEP_RELEASES"
    exit 1
fi

echo "Pruning public releases in $PUBLIC_REPO. Keeping latest $KEEP_RELEASES versioned releases..."

RELEASE_TAGS=()
while IFS= read -r tag; do
    [ -n "$tag" ] && RELEASE_TAGS+=("$tag")
done < <(
    gh api --paginate "repos/$PUBLIC_REPO/releases?per_page=100" \
        --jq '.[] | select(.draft == false and .prerelease == false and (.tag_name | test("^v[0-9]+\\.[0-9]+\\.[0-9]+$"))) | .tag_name'
)

TOTAL_RELEASES=${#RELEASE_TAGS[@]}
if [ "$TOTAL_RELEASES" -le "$KEEP_RELEASES" ]; then
    echo "Found $TOTAL_RELEASES versioned releases. Nothing to delete."
    exit 0
fi

for ((i = KEEP_RELEASES; i < TOTAL_RELEASES; i++)); do
    OLD_TAG="${RELEASE_TAGS[$i]}"
    if gh release view "$OLD_TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
        echo "Deleting old public release: $OLD_TAG"
        if ! gh release delete "$OLD_TAG" --repo "$PUBLIC_REPO" --yes --cleanup-tag; then
            if gh release view "$OLD_TAG" --repo "$PUBLIC_REPO" >/dev/null 2>&1; then
                echo "ERROR: Failed to delete $OLD_TAG from $PUBLIC_REPO"
                exit 1
            fi
            echo "Release $OLD_TAG was already deleted by another job."
        fi
    else
        echo "Release $OLD_TAG already deleted."
    fi
done

echo "Public release retention complete."
