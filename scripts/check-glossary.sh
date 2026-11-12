#!/usr/bin/env bash
set -e

# Scans production codebase (excluding prototypes) for prohibited domain terminology from GLOSSARY.md
BANNED_PATTERN="\b(smartwatch|fitness tracker|cardio zone|pulse range|heart bracket|max bpm|peak heart rate|top pulse|pulse reading|resting pulse|\bpulse\b|max heart rate|saved sensor|trusted peripheral|user account|personal info)\b"

TARGET_DIR="app/src/main"

echo "Checking production source files for forbidden GLOSSARY.md domain terms..."

MATCHES=$(grep -riIn -E "$BANNED_PATTERN" "$TARGET_DIR" --exclude-dir="prototype" 2>/dev/null || true)

if [ -n "$MATCHES" ]; then
    echo "ERROR: Forbidden domain terminology found in production sources:"
    echo "$MATCHES"
    echo ""
    echo "Please align terminology with GLOSSARY.md:"
    echo " - Use 'Heart Rate Monitor (HRM)' instead of 'smartwatch / fitness tracker / peripheral'"
    echo " - Use 'Heart Zone' instead of 'cardio zone / pulse range'"
    echo " - Use 'Maximum Heart Rate (HRmax)' instead of 'max heart rate / max bpm / peak heart rate / top pulse'"
    echo " - Use 'Resting Heart Rate (resting HR)' instead of 'resting pulse / pulse'"
    echo " - Use 'Remembered HRM' instead of 'saved sensor / trusted peripheral'"
    echo " - Use 'User Profile' instead of 'user account / personal info'"
    exit 1
fi

echo "Domain terminology check passed: 0 violations found."
exit 0
