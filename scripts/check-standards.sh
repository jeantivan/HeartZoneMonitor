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

# 4. Ban Material Icons imports (not in gradle/libs.versions.toml)
ICON_MATCHES=$(grep -rnE '^import[[:space:]]+androidx\.compose\.material\.icons\.' app/src --include='*.kt' --include='*.java' 2>/dev/null || true)
if [ -n "$ICON_MATCHES" ]; then
    echo "ERROR: Forbidden Material Icons import found in app/src (not included in dependencies):"
    echo "$ICON_MATCHES"
    FAILED=1
fi

# 5. Ban raw Athletic Monolith palette literals in ui/ outside ui/theme/ (use ui.theme tokens)
PALETTE_MATCHES=$(grep -rniE 'Color\(0xFF(0B0F19|1E293B|334155|BDCF73|00DAF3|F59E0B|EF4444|94A3B8|64748B|FD5921|10B981)\)' app/src/main/java/com/example/heartzonemonitor/ui --include='*.kt' 2>/dev/null | grep -v '/ui/theme/' || true)
if [ -n "$PALETTE_MATCHES" ]; then
    echo "ERROR: Raw Athletic Monolith palette literal found outside ui/theme/ (import the token from com.example.heartzonemonitor.ui.theme):"
    echo "$PALETTE_MATCHES"
    FAILED=1
fi

# 6. Prevent eCryptfs 143-byte .class filename overflow on coroutine @Test methods
LONG_TEST_MATCHES=$(awk '
    /class[[:space:]]+[A-Za-z0-9_]+/ {
        if (match($0, /class[[:space:]]+[A-Za-z0-9_]+/)) {
            s = substr($0, RSTART, RLENGTH)
            sub(/^class[[:space:]]+/, "", s)
            cls = s
        }
    }
    /fun[[:space:]]+`[^`]+`/ {
        if (match($0, /fun[[:space:]]+`[^`]+`/)) {
            t = substr($0, RSTART, RLENGTH)
            sub(/^fun[[:space:]]+`/, "", t)
            sub(/`$/, "", t)
            pending_name = t
            pending_line = FNR
            window = 4
        }
    }
    window > 0 {
        if ($0 ~ /runTest/) {
            combined = length(cls) + length(pending_name)
            if (combined > 122) {
                printf "%s:%d: %s$%s (len %d > 122)\n", FILENAME, pending_line, cls, pending_name, combined
            }
            window = 0
        } else {
            window--
        }
    }
' $(find app/src/test -name '*.kt' 2>/dev/null) || true)
if [ -n "$LONG_TEST_MATCHES" ]; then
    echo "ERROR: Coroutine test name too long for encrypted-filesystem (143-byte .class filename limit):"
    echo "$LONG_TEST_MATCHES"
    FAILED=1
fi

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi

echo "Coding standards check passed: 0 violations found."
exit 0
