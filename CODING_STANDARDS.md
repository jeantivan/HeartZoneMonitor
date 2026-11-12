# Coding Standards

## 1. Domain Terminology

Code, comments, types, and database identifiers must strictly align with `GLOSSARY.md`:
- Use `User Profile` (avoid `User settings`, `user account`, `personal info`).
- Use `Heart Rate Monitor (HRM)` (avoid `Smartwatch`, `fitness tracker`, `peripheral`).
- Use `Session` (avoid `Workout`, `activity`, `run`).
- Use `Maximum Heart Rate (HRmax)` (avoid `Max BPM`, `peak heart rate`).
- Use `Elapsed Duration`, `Effective Duration`, and `Paused Duration`.
- Use `Heart Zone` (avoid `Cardio zone`, `pulse range`).
- Use `Heart Rate Sample` (avoid `Tick`, `data point`).
- Use `Remembered HRM` and `Active HRM`.

## 2. Coroutines & Reactive Flow Testing

- **Prohibit sleep polling**: Never use `Thread.sleep()` or busy-waiting polling loops to assert reactive Flow emissions or asynchronous state in unit tests.
- **Deterministic collection**:
  - For single emission snapshots, use `flow.first()`.
  - For multi-emission state transitions, collect into a coroutine `Channel(Channel.UNLIMITED)` and assert with suspending `channel.receive()`, or use Turbine test collectors.

## 3. Room Persistence & Invariants

- **Singleton enforcement**: Tables with singleton requirements (e.g. `user_profile`) must enforce the singleton constraint (`id = 1`) at both the Kotlin model layer (`init { require(id == 1) }`) and the database layer (SQLite triggers or constraints).
- **Zero destructive migrations**: In accordance with `ADR-0001`, `fallbackToDestructiveMigration` is strictly forbidden. Schema changes must use explicit, automated migration scripts.
- **WAL & Foreign Keys**: SQLite connections must enforce `PRAGMA foreign_keys = ON;` and `PRAGMA journal_mode = WAL;`.
- **Integrity validation**: The database must execute `PRAGMA cipher_integrity_check;` on open.

## 4. Domain Calculations & Purity

- Calculation engines (such as `ZoneCalculator`) must remain pure, deterministic, and free of Android framework dependencies, enabling fast JVM unit test execution without instrumentation or Robolectric where possible.

## 5. Jetpack Compose UI Architecture

- **Event callback bundling (`Actions` pattern)**: Screen-level and major container composables with $\ge 3$ event lambdas must bundle them into a single `Actions` data class (e.g. `OnboardingProfileActions`) to eliminate parameter explosion and data clump smells across screen implementations, child cards, and unit test harnesses.
- **Stateless content separation**: Maintain a stateless `*Content(uiState, actions, modifier)` composable distinct from the stateful `*Screen(viewModel, onNavigate*, modifier)` wrapper to enable deterministic preview rendering and Robolectric Compose testing.
