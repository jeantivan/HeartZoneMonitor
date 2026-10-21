# Research HRmax formulas and Heart Zone percentage boundaries

Type: research
Status: resolved
Blocked by: none

## Question

Research the standard scientific formulas for calculating Maximum Heart Rate (Fox: `220 - age`, Tanaka: `208 - 0.7 * age`, Gellish: `207 - 0.7 * age`, Nes, and Karvonen / Heart Rate Reserve) based on age and resting heart rate, their accuracy across demographics, and the standard 5-zone percentage boundaries (Zone 1 to Zone 5). Determine the recommended default formula and configuration options for HeartZoneMonitor.

## Answer

Comprehensive scientific investigation conducted across 11 primary clinical and exercise physiology publications (JACC, MSSE, Circulation, HUNT study).

- **Branch**: `research/hrmax-and-zone-formulas`
- **Full Findings**: [docs/research/01-hrmax-and-zone-formulas.md](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/research/01-hrmax-and-zone-formulas.md) (on branch `research/hrmax-and-zone-formulas`)

### Key Takeaways:
1. **HRmax Prediction Formula**: 
   - **Tanaka et al. (2001)**: `HRmax = 208 - (0.7 * age)` is selected as the default. Supported by meta-analysis of 18,712 subjects; unisex validity; corrects the substantial age-bias of Fox (`220 - age`).
   - Fox (`220 - age`) has no experimental regression basis and severely underpredicts HRmax for adults > 40 by up to 10–16 bpm.
2. **Zone Calculation Paradigm (Adaptive Karvonen)**:
   - When **Resting Heart Rate (HRrest)** is provided: Use **Heart Rate Reserve (Karvonen / %HRR)**: `Target HR = HRrest + %intensity * (HRmax - HRrest)`. Swain & Leutholtz (1997) proved %HRR matches %VO2Reserve 1:1, adapting accurately to cardiovascular fitness and resting bradycardia.
   - When HRrest is omitted: Graceful fallback to **%HRmax**: `Target HR = %intensity * HRmax`.
3. **5-Zone Percentage Boundaries**:
   - Zone 1 (Active Recovery): 50% – 60%
   - Zone 2 (Aerobic Base / FatMax): 60% – 70% (bounded by LT1 / VT1)
   - Zone 3 (Tempo / Aerobic Endurance): 70% – 80% (metabolic transition)
   - Zone 4 (Lactate Threshold): 80% – 90% (centered at LT2 / VT2 / MLSS)
   - Zone 5 (VO2max / Anaerobic): 90% – 100%
4. **Input Hierarchy for Accuracy**:
   - All formulas have a Standard Error of Estimate (SEE) of $\approx \pm 10\text{--}11\text{ bpm}$. Therefore, a manual **Measured HRmax Override** is provided to allow athletes with stress tests to bypass estimation completely.
