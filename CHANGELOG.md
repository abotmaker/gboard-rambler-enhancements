# Changelog

## v1.0.0

Initial standalone release of **Gboard Rambler Enhancements** for Morphe:

- **Rambler Waveform Animation (Hex)**:
  - **Listening animations** (plays while you speak): **Hex Dot Scope** (braided dual-trace dot oscilloscope with phosphor trails), **Hex Android Wave** (7-row scrolling dot sine wave), or **Gboard Default**.
  - **Processing animations** (plays briefly after you stop speaking while Rambler transcribes): **Flow**, **Pulse**, or **Sweep**.
  - Independent **Listening color** and **Processing color** palettes + custom HSV/Hex picker.
  - Bare-minimum **Hex tuning**: Glow style (**Strip trace** vs **iOS halo**), dot spacing, glow strength, and voice response.
- **Rambler Custom Instructions**: enables `agentic_dictation_enable_custom_instruction` and adds custom instruction editing to both Gboard's Rambler settings and the unified **Rambler Enhancements** screen.
- **Rambler Personal Dictionary (`MUSE_STATS`)**: enables `UserFeatureCache` biasing words with add, `.txt` import/export, remove, and clear controls.
- **Rambler Contact Names**: seeds up to 1,000 phone contact names into `MUSE_STATS` with manual sync and history reset.
- **Rambler Cursor-Aware Editing**: enables cursor-aware editing, selected-text-only spoken edits, post-edit reselection, and a live **Append-only dictation** toggle with clean cursor-boundary spacing.
- **Rambler App Context**: enables sending active app/field metadata so Rambler matches app context.
- **Zero-conflict architecture alongside upstream GboardWu**:
  - Hooks Gboard's internal flag resolution (`oki.x()`) rather than `oki.g()`, so GboardWu's flag composer never conflicts.
  - Hooks `AgenticDictationExtension.<init>` rather than `hU()`, so GboardWu's Rambler selector never conflicts.
  - Uses dynamic bytecode fingerprinting (no pinned version restriction) — **verified on Gboard `18.5.4.993608717-release-arm64-v8a`**; other/newer versions are unverified.
