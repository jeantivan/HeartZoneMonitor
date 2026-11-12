# 26: LTTB Downsampling Algorithm for Time-Series Samples

**What to build:** A pure functional implementation of the Largest-Triangle-Three-Buckets (LTTB) downsampling algorithm, reducing massive 1 Hz sample arrays down to 600–800 visual points for 60/120fps charting while preserving critical peaks and troughs.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Implements pure functional `downsampleLttb(samples: List<HeartRateSample>, targetCount: Int): List<HeartRateSample>`.
- [ ] Divides samples into equal-sized buckets and selects the point in each bucket that maximizes triangle area formed with adjacent bucket points.
- [ ] Always preserves the exact first and last samples.
- [ ] Strictly guarantees retention of peak maximum BPMs and valley minimum BPMs.
- [ ] If sample list size is less than or equal to `targetCount`, returns original list without modification.
- [ ] Comprehensive unit tests assert output size, execution speed on 10,800-sample lists (3-hour session) in $< 15\text{ms}$, and preservation of extreme values.
