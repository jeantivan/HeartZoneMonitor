# 02: Physiological Zone Calculator Engine

**What to build:** A pure, dependency-free domain calculation engine that computes Maximum Heart Rate (HRmax) and 5 standard Heart Zone boundaries based on user age, resting heart rate, and optional manual overrides.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Calculates Maximum Heart Rate using the Tanaka formula: `HRmax = 208 - (0.7 * age)`.
- [ ] Supports direct manual override of Maximum Heart Rate, bypassing formula estimation when provided.
- [ ] Calculates Heart Zone boundaries using Karvonen Heart Rate Reserve (%HRR): `Target HR = HRrest + % * (HRmax - HRrest)` when resting heart rate is provided.
- [ ] Falls back cleanly to %HRmax calculation: `Target HR = % * HRmax` when resting heart rate is omitted.
- [ ] Defines 5 standardized Heart Zone boundaries: Zone 1 (50–60%), Zone 2 (60–70%), Zone 3 (70–80%), Zone 4 (80–90%), Zone 5 (90–100%).
- [ ] Maps each Heart Zone to its designated color palette: Zone 1 Cool Slate (`#64748B`), Zone 2 Vibrant Blue (`#3B82F6`), Zone 3 Emerald Green (`#10B981`), Zone 4 Vivid Amber (`#F59E0B`), Zone 5 Crimson (`#EF4444`).
- [ ] Pure functional domain unit tests verify boundary calculations and edge cases across ages 10 to 110.
