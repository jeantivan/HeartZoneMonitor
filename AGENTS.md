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

- Next open tickets: `./scripts/next-tickets.sh`
- Terminology check: `./scripts/check-glossary.sh`
- Standards check: `./scripts/check-standards.sh`
- Unit tests: `./gradlew :app:testDebugUnitTest --tests "<class>"`
- Full test suite: `./gradlew test`
- Android Lint: `./gradlew :app:lintDebug`
- Always pass `BypassSandbox: true` for `./gradlew` and `.git` index writes (`git add`, `git commit`, `git cherry-pick`, `git apply --cached`) — sandboxed runs cannot lock `~/.gradle` or `.git/index.lock`.

### Swarm Coordination

- Keep coordinator pre-flight reads minimal: read `./scripts/next-tickets.sh` output, target issue files, `.scratch/heart-zone-monitor/map.md`, and shared ownership entrypoints; delegate deep source and test reading to workers.
