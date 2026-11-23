# Changelog

## [1.1.4](https://github.com/abotmaker/gboard-rambler-enhancements/compare/v1.1.3...v1.1.4) (2026-11-23)

### ✨ Improvements

* **Gboard:** Show **Gboard Rambler Enhancements** directly inside Morphe Manager's **Need help finding the APK?** picker (`18.5.4.993608717-release-arm64-v8a` stable + `18.8.0.1022711984-beta-arm64-v8a` experimental).
* **Gboard:** Make the bundle a single-app Gboard source (`com.google.android.inputmethod.latin`) so Morphe Manager automatically shows the **Updated / Repatch** indicator and changelog on installed Gboard / GboardWu builds whenever a new release ships.

## [1.1.3](https://github.com/abotmaker/gboard-rambler-enhancements/compare/v1.1.2...v1.1.3) (2026-11-23)

### 🐛 Bug Fixes

* **Apply Rambler flag overrides on initial flag creation (`oki.x()`):** Insert `RamblerFlagRuntime.applyResolvedFlag(...)` before the **first** post-loop `if-eqz vSnapshot` check (`if (okrVar != null)`) instead of matching a later `if-eqz` where R8 reused `v0` for `this.e`. Previously, default-only flags (`agentic_dictation_enable_custom_instruction`, `enable_user_feature_cache`, `show_rambler_dict_settings`, `jetson_enable_cursor_awareness`, `agentic_dictation_enable_app_info_context`) skipped the hook when initialized for the first time (`okrVar == null`), preventing Custom Instructions, MUSE_STATS Personal Dictionary / Contact Names, Cursor-Aware Editing, and App Context from activating at runtime.

## [1.1.2](https://github.com/abotmaker/gboard-rambler-enhancements/compare/v1.1.1...v1.1.2) (2026-11-23)

### 🐛 Bug Fixes

* **Fix ART DEX `VerifyError` in `RamblerDynamicConfig.<clinit>()`:** D8 folded static constant defaults into DEX `static_values` with a 0-register `<clinit>`, so rewriting `<clinit>` in place left `registers = 0` while referencing `v0` (`register index out of range (0 >= 0)`), preventing all 6 Rambler features and the explicit Android 14–17 settings launcher from initializing at runtime. `rewriteDynamicConfigClinit` now replaces `<clinit>` with a fresh `MutableMethodImplementation(2)`.
* **Preserve `.catch` try/catch metadata on `ramblerEnhancementsAppendCommit`:** Copy `.catch` exception handlers onto `MutableMethodImplementation` in `addHelperMethodIfMissing` so ART's DEX verifier validates the `move-exception` handler block in `AgenticDictationExtension` cleanly.

## [1.1.1](https://github.com/abotmaker/gboard-rambler-enhancements/compare/v1.1.0...v1.1.1) (2026-11-23)

### 🐛 Bug Fixes

* **Android 14–17 settings launch:** Fix *"No application found to handle the action"* when tapping **Waveform animation (Hex)**, **Manage voice dictionary**, **Contact names sync**, or **Rambler Enhancements** on Android 14+ (API 34–37) by converting the internal preference intent into an explicit `ComponentName(context.getPackageName(), RamblerEnhancementsActivity)` inside `Preference.performClick()` (fully compatible with GboardWu's `Package Rename` and `android:exported="false"`).
* **Edge-to-edge padding:** Enable `fitsSystemWindows` on `RamblerEnhancementsActivity` so content clears the status and navigation bars on Android 15–17.
* **Morphe source manifest:** Use `LocalDateTime` format (without trailing `Z`) in `patches-bundle.json` so Morphe Manager parses updates cleanly.

## [1.1.0](https://github.com/abotmaker/gboard-rambler-enhancements/compare/v1.0.0...v1.1.0) (2026-11-23)

### ✨ New Features

* **Rambler voice typing settings:** Expose every selected patch directly inside Gboard's native **Settings → Voice typing → Rambler voice typing** screen in Material You style:
  * **Custom instructions** — inline dialog editor.
  * **Waveform** → **Waveform animation (Hex)** — jumps directly to Listening/Processing animations, colors, and Hex tuning.
  * **Voice dictionary & contacts** → live **Use dictionary words** switch (`enable_user_contact_biasing`), **Manage voice dictionary** (`MUSE_STATS` Add / Import `.txt` / Export `.txt` / Remove / Clear), and **Contact names sync**.
  * **Dictation behavior** → live native switches right on the page for **Append-only dictation**, **Cursor-aware editing**, and **App context** (synced live with **Voice typing → Rambler Enhancements**).

## [1.0.0](https://github.com/abotmaker/gboard-rambler-enhancements/releases/tag/v1.0.0) (2026-11-23)

### ✨ New Features

* **Rambler Waveform Animation (Hex):** Ported from [Hex by Anomaly](https://github.com/anomalyco/hex) (MIT):
  * **Listening** *(while you speak)*: **Hex Dot Scope** (braided dual-trace dot oscilloscope with phosphor trails), **Hex Android Wave** (7-row scrolling dot sine wave), or **Gboard Default**.
  * **Processing** *(after you stop speaking, while transcribing)*: **Flow**, **Pulse**, or **Sweep**.
  * Independent **Listening** & **Processing** colors + minimal **Hex tuning** (**Strip trace** vs **iOS halo**, dot spacing, glow strength, voice response).
* **Rambler Custom Instructions:** Enables `agentic_dictation_enable_custom_instruction` and persistent custom style/formatting rules.
* **Rambler Personal Dictionary (`MUSE_STATS`):** Enables `UserFeatureCache` biasing words with add, `.txt` import/export, remove, and clear controls.
* **Rambler Contact Names:** Seeds up to 1,000 phone contact names into `MUSE_STATS` with manual sync and history reset.
* **Rambler Cursor-Aware Editing:** Enables cursor-aware editing, selected-text-only spoken edits, post-edit reselection, and a live **Append-only dictation** toggle with clean cursor-boundary spacing.
* **Rambler App Context:** Enables sharing active app/field metadata with a live toggle.
* **GboardWu coexistence:** Conflict-free hooks (`oki.x()` and `AgenticDictationExtension.<init>`) with dynamic bytecode fingerprinting — verified on Gboard `18.5.4.993608717-release-arm64-v8a`; newer versions are unverified.
