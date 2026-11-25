# 26: LTTB Downsampling Algorithm for Time-Series Samples

**What to build:** A pure functional implementation of the Largest-Triangle-Three-Buckets (LTTB) downsampling algorithm, reducing massive 1 Hz sample arrays down to 600–800 visual points for 60/120fps charting while preserving critical peaks and troughs.

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Implements pure functional `downsampleLttb(samples: List<HeartRateSample>, targetCount: Int): List<HeartRateSample>`.
- [x] Divides samples into equal-sized buckets and selects the point in each bucket that maximizes triangle area formed with adjacent bucket points.
- [x] Always preserves the exact first and last samples.
- [x] Strictly guarantees retention of peak maximum BPMs and valley minimum BPMs.
- [x] If sample list size is less than or equal to `targetCount`, returns original list without modification.
- [x] Comprehensive unit tests assert output size, execution speed on 10,800-sample lists (3-hour session) in $< 15\text{ms}$, and preservation of extreme values.

## Answer

Implemented the pure functional LTTB downsampling engine in `domain/calculator/`:

- **`LttbDownsampler.kt`** (`app/src/main/java/com/example/heartzonemonitor/domain/calculator/LttbDownsampler.kt`):
  - Implemented `downsampleLttb(samples: List<HeartRateSample>, targetCount: Int): List<HeartRateSample>`.
  - Returns `samples` unmodified when `samples.size <= targetCount` and validates `targetCount >= 3` when downsampling is required.
  - Partitions interior samples (`1 .. size - 2`) into `targetCount - 2` equal-sized buckets, computes each next bucket's centroid `(avgX, avgY)` using relative epoch offsets (`timestampEpochMs - baseTimestamp`), and selects the point maximizing triangle area against the previously selected point and next bucket centroid.
  - Always preserves `samples.first()` and `samples.last()`, and guarantees retention of global maximum and minimum BPMs in ascending sample order while preferring the extremum's own bucket slot.
- **`LttbDownsamplerTest.kt`** (`app/src/test/java/com/example/heartzonemonitor/domain/calculator/LttbDownsamplerTest.kt`):
  - Pure JVM unit tests covering passthrough (`size <= targetCount`), hand-calculated LTTB triangle-area bucket selection and endpoint retention, contested/same-bucket global max & min BPM retention, invalid `targetCount` validation, and 10,800-sample (3-hour) execution under 15 ms.
