# Coding Standards

Mechanical rules (banned terms, `Thread.sleep` in tests, destructive migrations, Android/`data`/`ui` imports in `domain/`, Material Icons imports, raw palette literals outside `ui/theme/`) are enforced by `./scripts/check-glossary.sh` and `./scripts/check-standards.sh`. This file holds judgement calls for review.

## 1. Domain Terminology

Code, comments, types, and database identifiers align with `GLOSSARY.md`; its `_Avoid_` terms are the banned vocabulary. When Stitch mockups or `docs/design/screens.md` use pre-glossary copy (`Workout`, `MAX BPM`, `Saved Sensor`), render the `GLOSSARY.md` equivalents (`Session`, `MAX HR` + `BPM`, `Remembered HRM`).

## 2. Coroutines & Reactive Flow Testing

- Assert `Flow` emissions deterministically: `flow.first()` for single snapshots; a coroutine `Channel(Channel.UNLIMITED)` with suspending `channel.receive()` (or Turbine) for multi-emission transitions. (`StateFlow.value` after `advanceUntilIdle()` / `runCurrent()` is acceptable only for conflated single-state checks, not intermediate transitions.)

## 3. Room Persistence & Invariants

- **Singleton enforcement**: Tables with singleton requirements (e.g. `user_profile`) enforce the constraint (`id = 1`) at both the Kotlin model layer (`init { require(id == 1) }`) and the database layer (SQLite triggers or constraints).
- **Explicit migrations**: Per `ADR-0001`, schema changes ship as explicit, automated migration scripts.
- **WAL & Foreign Keys**: SQLite connections enforce `PRAGMA foreign_keys = ON;` and `PRAGMA journal_mode = WAL;`.
- **Integrity validation**: The database executes `PRAGMA cipher_integrity_check;` on open.

## 4. Domain Calculations & Purity

- Calculation engines (such as `ZoneCalculator`) stay pure and deterministic, so they run as fast JVM unit tests without instrumentation or Robolectric where possible.

## 5. Jetpack Compose UI Architecture

- **Event callback bundling (`Actions` pattern)**: Screen-level and major container composables with $\ge 3$ event lambdas bundle them into a single `Actions` data class (e.g. `OnboardingProfileActions`).
- **Stateless content separation**: Keep a stateless `*Content(uiState, actions, modifier)` composable distinct from the stateful `*Screen(viewModel, onNavigate*, modifier)` wrapper.
- **Theme tokens**: Colors come from `ui/theme/Color.kt` (Athletic Monolith tokens from `docs/design/DESIGN_SYSTEM.md`); add new tokens there instead of declaring file-local palettes.
