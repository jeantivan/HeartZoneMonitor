#!/usr/bin/env bash
set -e

echo "Checking codebase for mechanical CODING_STANDARDS.md violations..."

FAILED=0

# 1. Ban Thread.sleep in unit tests (outside comments)
SLEEP_MATCHES=$(grep -rnE 'Thread\.sleep' app/src/test --include='*.kt' --include='*.java' 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(//|/\*|\*)' || true)
if [ -n "$SLEEP_MATCHES" ]; then
    echo "ERROR: Forbidden Thread.sleep found in app/src/test (use Flow/Channel/Turbine or virtual test time):"
    echo "$SLEEP_MATCHES"
    FAILED=1
fi

# 2. Ban fallbackToDestructiveMigration in production sources (outside comments)
DESTRUCTIVE_MATCHES=$(grep -rnE 'fallbackToDestructiveMigration' app/src/main --include='*.kt' --include='*.java' 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(//|/\*|\*)' || true)
if [ -n "$DESTRUCTIVE_MATCHES" ]; then
    echo "ERROR: Forbidden fallbackToDestructiveMigration found in app/src/main (ADR-0001 requires explicit migrations):"
    echo "$DESTRUCTIVE_MATCHES"
    FAILED=1
fi

# 3. Ban android.* / androidx.* imports in pure domain layer
DOMAIN_DIR="app/src/main/java/com/example/heartzonemonitor/domain"
if [ -d "$DOMAIN_DIR" ]; then
    ANDROID_IMPORT_MATCHES=$(grep -rnE '^import[[:space:]]+(android|androidx)\.' "$DOMAIN_DIR" --include='*.kt' --include='*.java' 2>/dev/null || true)
    if [ -n "$ANDROID_IMPORT_MATCHES" ]; then
        echo "ERROR: Android framework imports found in pure domain package ($DOMAIN_DIR):"
        echo "$ANDROID_IMPORT_MATCHES"
        FAILED=1
    fi
fi

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi

echo "Coding standards check passed: 0 violations found."
exit 0
