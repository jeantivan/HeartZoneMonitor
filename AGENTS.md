# HeartZoneMonitor

## Agent skills

### Issue tracker

Issues and specs live under `.scratch/heart-zone-monitor/issues/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses canonical triage labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context (`GLOSSARY.md` and `docs/adr/` at the repo root). See `docs/agents/domain.md`.

### Coding standards

Codebase standards and review rules live in `CODING_STANDARDS.md`.

### Commands

- Unit tests: `./gradlew :app:testDebugUnitTest --tests "<class>"`
- Full test suite: `./gradlew test`
- Build & test environment relies on host-level JDK and Android SDK paths.
