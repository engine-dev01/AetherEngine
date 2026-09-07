#!/bin/bash
# test-ci.sh — Layer 3: Branch test CI before merge to main
#
# Usage: scripts/test-ci.sh <branch-name>
#   e.g., scripts/test-ci.sh test/fix-typo
#
# Steps:
#   1. Pre-flight L1 (G1-G7)
#   2. Pre-flight L2 (test-local.sh, no SDK = skip)
#   3. git push to branch
#   4. Poll CI build (max 10 min)
#   5. Report pass/fail
#   6. If pass, offer merge to main (interactive prompt)
set -e

cd "$(dirname "$0")/.."
BRANCH="${1:-}"

if [ -z "$BRANCH" ]; then
    echo "Usage: $0 <branch-name>"
    echo "  Example: $0 test/fix-typo"
    exit 1
fi

# Pre-flight L1
echo "═══════════════════════════════════════════════════"
echo " Pre-flight L1 (G1-G7)"
echo "═══════════════════════════════════════════════════"
bash scripts/preflight.sh 2>&1 | tail -10

# Pre-flight L2
echo ""
echo "═══════════════════════════════════════════════════"
echo " Pre-flight L2 (Local)"
echo "═══════════════════════════════════════════════════"
bash scripts/test-local.sh 2>&1 | tail -5

# Git status
echo ""
echo "═══════════════════════════════════════════════════"
echo " Git status"
echo "═══════════════════════════════════════════════════"
git status --short
if [ -n "$(git status --short)" ]; then
    echo "❌ Working tree not clean — commit first"
    exit 1
fi

# Current branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
echo "Current branch: $CURRENT_BRANCH"
echo "Test branch:    $BRANCH"
echo ""

# Push to test branch
echo "═══════════════════════════════════════════════════"
echo " Push to origin/$BRANCH"
echo "═══════════════════════════════════════════════════"
git push origin HEAD:"$BRANCH" 2>&1 | tail -3

# Get CI run ID
echo ""
echo "═══════════════════════════════════════════════════"
echo " Poll CI (max 10 min)"
echo "═══════════════════════════════════════════════════"
START=$(date +%s)
MAX_WAIT=600
RUN_ID=""
for i in $(seq 1 40); do
    ELAPSED=$(( $(date +%s) - START ))
    if [ $ELAPSED -gt $MAX_WAIT ]; then echo "⏱️ timeout"; break; fi

    sleep 5  # wait for CI to register
    RESP=$(curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
        "https://api.github.com/repos/aether-dev-oss/AetherEngine/actions/runs?per_page=5" 2>&1)
    BUILD=$(echo "$RESP" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    for r in d.get('workflow_runs', []):
        if r['head_branch'] == '$BRANCH':
            print(f\"{r['id']}|{r['name']}|{r['status']}|{r.get('conclusion') or '-'}|\")
            break
except: pass")
    if [ -n "$BUILD" ]; then
        IFS='|' read -r ID NAME STATUS CONCL _ <<< "$BUILD"
        echo "  t+${ELAPSED}s | $NAME | $STATUS | $CONCL"
        RUN_ID="$ID"
        if [ "$STATUS" = "completed" ]; then break; fi
    fi
    sleep 10
done

# Get final status
RESP=$(curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/aether-dev-oss/AetherEngine/actions/runs?per_page=3" 2>&1)
STATUS=$(echo "$RESP" | python3 -c "
import json, sys
d = json.load(sys.stdin)
for r in d.get('workflow_runs', []):
    if r['head_branch'] == '$BRANCH':
        print(r.get('conclusion', 'unknown'))
        break")

echo ""
echo "═══════════════════════════════════════════════════"
echo " Result: $STATUS"
echo "═══════════════════════════════════════════════════"

if [ "$STATUS" = "success" ]; then
    echo "✅ CI build passed"
    echo ""
    read -p "Merge to main? (y/n) " answer
    if [ "$answer" = "y" ]; then
        git checkout main
        git merge --ff-only "$BRANCH"
        git push origin main
        git branch -d "$BRANCH"
        git push origin --delete "$BRANCH"
        echo "✅ Merged and cleaned up"
    else
        echo "⏭️ Skipped merge (branch remains)"
    fi
elif [ "$STATUS" = "failure" ]; then
    echo "❌ CI build FAILED — check:"
    echo "  https://github.com/aether-dev-oss/AetherEngine/actions/runs/$RUN_ID"
    echo ""
    echo "Fix issues, commit, then re-run: scripts/test-ci.sh $BRANCH"
    exit 1
else
    echo "⏱️ CI still running or unknown status: $STATUS"
    echo "Check: https://github.com/aether-dev-oss/AetherEngine/actions/runs/$RUN_ID"
    exit 1
fi
