# Stitch Screen Mockups & Prototypes

This directory holds the assets exported from [stitch.withgoogle.com](https://stitch.withgoogle.com).

## Export Structure

When exporting from Stitch:
1. Place the exported project-level **`DESIGN.md`** directly in this folder (`docs/design/stitch/DESIGN.md`).
2. For each screen, place the exported **`screen.png`** (or mockup image) and **`code.html`** in its respective subfolder:

```text
docs/design/stitch/
├── DESIGN.md                      # Single project-level design document from Stitch
├── 01-onboarding-profile/         # screen.png & code.html
├── 02-onboarding-pairing/         # screen.png & code.html
├── 03-live-session/               # screen.png & code.html
├── 04-session-history/            # screen.png & code.html
├── 05-session-detail/             # screen.png & code.html
├── 06-device-management/          # screen.png & code.html
└── 07-settings-profile/           # screen.png & code.html
```

Use the prompts in [`docs/design/stitch-prompts.md`](file:///home/jeanpaul/AndroidStudioProjects/HeartZoneMonitor/docs/design/stitch-prompts.md) to generate the screens in Stitch.
