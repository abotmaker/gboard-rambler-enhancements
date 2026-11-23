package dev.abotmaker.ramblerenhancements;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Unified standalone DayNight settings activity launched from Gboard Settings
 * (Voice typing / Voice typing -> Rambler -> Rambler Enhancements).
 *
 * <p>Zero dependency on GboardWu or AndroidX. Shows only the sections corresponding to the
 * patches selected in Morphe ({@link RamblerDynamicConfig}{@code .FEATURE_*}).</p>
 */
public final class RamblerEnhancementsActivity extends Activity {
    private static final String TAG = "RamblerEnhancements";
    private static final int REQ_IMPORT_WORDS = 4101;
    private static final int REQ_EXPORT_WORDS = 4102;
    private static final int REQ_CONTACTS_PERMISSION = 4103;
    private static final int PREVIEW_WORD_LIMIT = 12;
    private static final String EXPORT_FILE_NAME = "rambler-voice-dictionary.txt";

    private static final String[] LISTENING_VALUES = {
            RamblerWaveformSettings.LISTENING_HEX_DOT_SCOPE,
            RamblerWaveformSettings.LISTENING_HEX_ANDROID_WAVE,
            RamblerWaveformSettings.LISTENING_STOCK
    };
    private static final String[] LISTENING_LABELS = {
            "Hex Dot Scope",
            "Hex Android Wave",
            "Gboard Default"
    };
    private static final String[] LISTENING_DESCS = {
            "Braided dual-trace dot oscilloscope with phosphor trails (Hex macOS/iOS).",
            "7-row scrolling dot sine wave driven by live speech level (Hex Android).",
            "Disable Hex animations and use Gboard's stock Rambler light bar."
    };

    private static final String[] PROCESSING_VALUES = {
            RamblerWaveformSettings.PROCESSING_FLOW,
            RamblerWaveformSettings.PROCESSING_PULSE,
            RamblerWaveformSettings.PROCESSING_SWEEP
    };
    private static final String[] PROCESSING_LABELS = {
            "Flow",
            "Pulse",
            "Sweep"
    };
    private static final String[] PROCESSING_DESCS = {
            "Braid keeps turning while light flows left to right.",
            "Light rings pulse outward from the center.",
            "Braid flattens into a line and a light beam sweeps back and forth."
    };

    private static final String[] COLOR_VALUES = {
            RamblerWaveformSettings.COLOR_HEX_DEFAULT,
            RamblerWaveformSettings.COLOR_THEME_AUTO,
            RamblerWaveformSettings.PRESET_PEARL,
            RamblerWaveformSettings.PRESET_AGENT_BLUE,
            RamblerWaveformSettings.PRESET_CORAL,
            RamblerWaveformSettings.PRESET_EMERALD,
            RamblerWaveformSettings.PRESET_VIOLET,
            RamblerWaveformSettings.PRESET_AMBER,
            RamblerWaveformSettings.PRESET_CYAN,
            RamblerWaveformSettings.PRESET_WHITE,
            RamblerWaveformSettings.COLOR_SELECTION_CUSTOM
    };
    private static final String[] LISTENING_COLOR_LABELS = {
            "Hex Pearl", "Theme accent", "Pearl", "Agent Blue", "Coral",
            "Emerald", "Violet", "Amber", "Cyan", "White", "Custom"
    };
    private static final String[] PROCESSING_COLOR_LABELS = {
            "Hex Blue", "Theme accent", "Pearl", "Agent Blue", "Coral",
            "Emerald", "Violet", "Amber", "Cyan", "White", "Custom"
    };

    public static final String EXTRA_SECTION = "section";
    public static final String SECTION_WAVEFORM = "waveform";
    public static final String SECTION_DICTIONARY = "dictionary";
    public static final String SECTION_CONTACTS = "contacts";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout contentContainer;
    private HexDotScopeSimulation.Stage previewStage = HexDotScopeSimulation.Stage.RECORDING;
    private String pendingExportPayload;
    private String activeSection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RamblerFlagRuntime.seedAndRefreshIfNeeded(this);
        RamblerWaveformRuntime.seedApplicationContext(this);
        Intent launchIntent = getIntent();
        activeSection = launchIntent == null ? null : launchIntent.getStringExtra(EXTRA_SECTION);
        setTitle(titleForSection(activeSection));
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(pal.background);
        scroll.setFillViewport(true);

        contentContainer = new LinearLayout(this);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setPadding(dp(16), dp(14), dp(16), dp(28));
        scroll.addView(contentContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item != null && item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExportPayload = null;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_WORDS) {
            runAsync(() -> {
                String content = readTextFromUri(uri);
                LinkedHashSet<String> words = RamblerMuseStatsBridge.parseWordList(content);
                if (words.isEmpty()) {
                    toastOnMain("No valid words found to import.");
                    return;
                }
                boolean ok = RamblerMuseStatsBridge.addWords(this, words);
                mainHandler.post(() -> {
                    showToast(ok
                            ? "Added " + words.size() + " words to Rambler voice dictionary."
                            : "Rambler dictionary isn't ready yet. Dictate once and try again.");
                    refreshUi();
                });
            });
        } else if (requestCode == REQ_EXPORT_WORDS) {
            final String payload = pendingExportPayload;
            pendingExportPayload = null;
            if (payload == null) {
                return;
            }
            runAsync(() -> {
                boolean ok = writeTextToUri(uri, payload);
                toastOnMain(ok
                        ? "Exported Rambler voice dictionary."
                        : "Failed to write export file.");
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACTS_PERMISSION) {
            boolean granted = grantResults != null
                    && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                syncContactsNow();
            } else {
                showToast("Contacts permission not granted.");
                refreshUi();
            }
        }
    }

    private static String titleForSection(String section) {
        if (SECTION_WAVEFORM.equals(section)) {
            return "Waveform animation (Hex)";
        }
        if (SECTION_DICTIONARY.equals(section)) {
            return "Rambler voice dictionary";
        }
        if (SECTION_CONTACTS.equals(section)) {
            return "Contact names sync";
        }
        return "Rambler Enhancements";
    }

    private boolean showSection(String sectionName) {
        return activeSection == null || activeSection.isEmpty() || sectionName.equals(activeSection);
    }

    private void refreshUi() {
        if (contentContainer == null) {
            return;
        }
        contentContainer.removeAllViews();

        if (RamblerDynamicConfig.FEATURE_WAVEFORM && showSection(SECTION_WAVEFORM)) {
            buildWaveformSection();
        }
        if (RamblerDynamicConfig.FEATURE_CUSTOM_INSTRUCTIONS && showSection("custom_instructions")) {
            buildCustomInstructionsSection();
        }
        if (RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY && showSection(SECTION_DICTIONARY)) {
            buildPersonalDictionarySection();
        }
        if (RamblerDynamicConfig.FEATURE_CONTACT_NAMES && showSection(SECTION_CONTACTS)) {
            buildContactsSection();
        }
        if ((RamblerDynamicConfig.FEATURE_CURSOR_EDITING || RamblerDynamicConfig.FEATURE_APP_CONTEXT)
                && showSection("behavior")) {
            buildDictationBehaviorSection();
        }

        // Credits footer
        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        TextView creditView = new TextView(this);
        creditView.setText(
                "Waveforms ported from Hex by Anomaly (github.com/anomalyco/hex, MIT). "
                        + "Rambler enhancements adapted with credit to nl-morphe-patches (GPLv3) "
                        + "and designed to work alongside GboardWu.");
        creditView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        creditView.setTextColor(pal.secondary);
        LinearLayout.LayoutParams creditParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        creditParams.topMargin = dp(10);
        contentContainer.addView(creditView, creditParams);
    }

    private void buildWaveformSection() {
        addSectionHeader("Waveform animation (Hex)");
        SharedPreferences prefs = RamblerWaveformSettings.preferencesOrNull(this);
        RamblerWaveformSettings.SettingsSnapshot snapshot =
                RamblerWaveformSettings.snapshotFromPreferences(prefs);
        boolean hexEnabled = !snapshot.isStock();
        if (!hexEnabled) {
            previewStage = HexDotScopeSimulation.Stage.RECORDING;
        }

        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        LinearLayout previewHeader = new LinearLayout(this);
        previewHeader.setOrientation(LinearLayout.HORIZONTAL);
        previewHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView previewLabel = new TextView(this);
        previewLabel.setText("Live preview");
        previewLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        previewLabel.setTextColor(pal.secondary);
        previewHeader.addView(previewLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        if (hexEnabled) {
            TextView stageToggle = new TextView(this);
            stageToggle.setText(previewStage == HexDotScopeSimulation.Stage.RECORDING
                    ? "Listening (tap for Processing)"
                    : "Processing (tap for Listening)");
            stageToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            stageToggle.setTypeface(Typeface.DEFAULT_BOLD);
            stageToggle.setTextColor(pal.accent);
            stageToggle.setPadding(dp(8), dp(2), dp(4), dp(2));
            stageToggle.setClickable(true);
            stageToggle.setFocusable(true);
            stageToggle.setOnClickListener(v -> {
                previewStage = previewStage == HexDotScopeSimulation.Stage.RECORDING
                        ? HexDotScopeSimulation.Stage.PROCESSING
                        : HexDotScopeSimulation.Stage.RECORDING;
                refreshUi();
            });
            previewHeader.addView(stageToggle);
        }
        contentContainer.addView(previewHeader);

        RamblerWaveformPreviewView preview = new RamblerWaveformPreviewView(this);
        preview.setPreviewState(snapshot, previewStage);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        previewParams.topMargin = dp(6);
        previewParams.bottomMargin = dp(12);
        contentContainer.addView(preview, previewParams);

        addRow(
                "Listening animation",
                "Plays while you speak. Choose Hex Dot Scope, Hex Android Wave, or Gboard Default.",
                displayListening(snapshot.getListeningStyle()),
                true,
                () -> RamblerWaveformDialogs.showAnimationChoiceDialog(
                        this,
                        "Listening animation",
                        "Shown while you speak. Tap an option to preview.",
                        LISTENING_LABELS,
                        LISTENING_DESCS,
                        LISTENING_VALUES,
                        snapshot.getListeningStyle(),
                        snapshot,
                        false,
                        selected -> {
                            RamblerWaveformSettings.writeListeningStyle(this, selected);
                            refreshUi();
                        }));

        addRow(
                "Processing animation",
                hexEnabled
                        ? "Plays after you stop speaking, while Rambler transcribes and formats."
                        : "Select a Hex listening animation above to customize.",
                hexEnabled ? displayProcessing(snapshot.getProcessingStyle()) : "Stock",
                hexEnabled,
                () -> RamblerWaveformDialogs.showAnimationChoiceDialog(
                        this,
                        "Processing animation",
                        "Shown briefly after you stop speaking while Rambler transcribes.",
                        PROCESSING_LABELS,
                        PROCESSING_DESCS,
                        PROCESSING_VALUES,
                        snapshot.getProcessingStyle(),
                        snapshot,
                        true,
                        selected -> {
                            RamblerWaveformSettings.writeProcessingStyle(this, selected);
                            refreshUi();
                        }));

        String listenSelection = RamblerWaveformSettings.readListeningColorSelection(prefs);
        String listenCustom = RamblerWaveformSettings.readListeningCustomColor(prefs);
        addRow(
                "Listening color",
                hexEnabled
                        ? "Waveform color shown while you speak."
                        : "Select a Hex listening animation above to customize.",
                hexEnabled ? displayColor(snapshot.getListeningColorValue(), false) : "Stock",
                hexEnabled,
                () -> openColorDialog(
                        "Listening waveform color",
                        snapshot.getListeningColorValue(),
                        listenSelection,
                        listenCustom,
                        snapshot,
                        false));

        String processSelection = RamblerWaveformSettings.readProcessingColorSelection(prefs);
        String processCustom = RamblerWaveformSettings.readProcessingCustomColor(prefs);
        addRow(
                "Processing color",
                hexEnabled
                        ? "Animation color shown while Rambler transcribes."
                        : "Select a Hex listening animation above to customize.",
                hexEnabled ? displayColor(snapshot.getProcessingColorValue(), true) : "Stock",
                hexEnabled,
                () -> openColorDialog(
                        "Processing waveform color",
                        snapshot.getProcessingColorValue(),
                        processSelection,
                        processCustom,
                        snapshot,
                        true));

        addRow(
                "Hex tuning",
                hexEnabled
                        ? "Glow style (Strip trace or iOS halo), dot spacing, glow strength, and voice response."
                        : "Select a Hex listening animation above to customize.",
                hexEnabled ? displayTuning(snapshot) : "Stock",
                hexEnabled,
                () -> RamblerWaveformDialogs.showTuningDialog(
                        this,
                        snapshot,
                        (glowLook, spacing, glow, response) -> {
                            RamblerWaveformSettings.writeTuning(
                                    this, glowLook, spacing, glow, response);
                            refreshUi();
                        }));
    }

    private void buildCustomInstructionsSection() {
        addSectionHeader("Custom instructions");
        String current = RamblerMuseStatsBridge.readCustomInstruction(this).trim();
        addRow(
                "Rambler custom instructions",
                current.isEmpty()
                        ? "Not set. Tap to add persistent style rules (spelling, tone, formatting)."
                        : current,
                current.isEmpty() ? "Edit" : "Set",
                true,
                () -> showTextInputDialog(
                        "Custom instructions",
                        "Style rules applied to every Rambler dictation (e.g. Write numbers as digits).",
                        current,
                        value -> {
                            boolean ok = RamblerMuseStatsBridge.writeCustomInstruction(this, value);
                            showToast(ok
                                    ? "Custom instructions saved."
                                    : "Could not save custom instructions.");
                            refreshUi();
                        }));
    }

    private void buildPersonalDictionarySection() {
        addSectionHeader("Voice dictionary (MUSE_STATS)");
        addToggleRow(
                "Use dictionary words",
                "Send stored MUSE_STATS biasing words with every Rambler dictation.",
                RamblerEnhancementsSettings.readUseDictionaryWords(this),
                checked -> {
                    RamblerEnhancementsSettings.writeUseDictionaryWords(this, checked);
                    refreshUi();
                });
        List<String> storedWords = RamblerMuseStatsBridge.readWords(this);
        addRow(
                "Stored words",
                formatDictionaryStatus(storedWords),
                storedWords == null ? "Wait" : Integer.toString(storedWords.size()),
                false,
                null);
        addRow(
                "Add words",
                "Enter names or jargon separated by commas or new lines.",
                "Add",
                true,
                () -> showTextInputDialog(
                        "Add voice dictionary words",
                        "One word/phrase per line or comma-separated.",
                        "",
                        raw -> {
                            LinkedHashSet<String> words =
                                    RamblerMuseStatsBridge.parseWordList(raw);
                            if (words.isEmpty()) {
                                showToast("No valid words entered.");
                                return;
                            }
                            runAsync(() -> {
                                boolean ok = RamblerMuseStatsBridge.addWords(this, words);
                                mainHandler.post(() -> {
                                    showToast(ok
                                            ? "Added " + words.size() + " word(s)."
                                            : "Dictionary not ready yet. Dictate once first.");
                                    refreshUi();
                                });
                            });
                        }));
        addRow(
                "Import words (.txt)",
                "Import words from a plain-text file (newline or comma separated).",
                "Import",
                true,
                () -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("text/*");
                        startActivityForResult(intent, REQ_IMPORT_WORDS);
                    } catch (Throwable err) {
                        Log.w(TAG, "Import picker failed", err);
                        showToast("Could not open file picker.");
                    }
                });
        addRow(
                "Export words (.txt)",
                "Save all stored Rambler biasing words to a plain-text file.",
                "Export",
                true,
                () -> {
                    List<String> words = RamblerMuseStatsBridge.readWords(this);
                    if (words == null) {
                        showToast("Dictionary not ready yet. Dictate once first.");
                        return;
                    }
                    if (words.isEmpty()) {
                        showToast("Voice dictionary is empty.");
                        return;
                    }
                    pendingExportPayload = RamblerMuseStatsBridge.formatExportText(words);
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME);
                        startActivityForResult(intent, REQ_EXPORT_WORDS);
                    } catch (Throwable err) {
                        Log.w(TAG, "Export picker failed", err);
                        pendingExportPayload = null;
                        showToast("Could not open save dialog.");
                    }
                });
        addRow(
                "Remove a word",
                "Delete a specific word from Rambler's voice dictionary.",
                "Remove",
                true,
                this::showRemoveWordDialog);
        addRow(
                "Clear all words",
                "Remove every stored word from MUSE_STATS.",
                "Clear",
                true,
                () -> showConfirmDialog(
                        "Clear all voice dictionary words?",
                        "This removes all words from Rambler's MUSE_STATS list.",
                        () -> runAsync(() -> {
                            boolean ok = RamblerMuseStatsBridge.clearAllWords(this);
                            mainHandler.post(() -> {
                                showToast(ok
                                        ? "Cleared Rambler voice dictionary."
                                        : "Dictionary not ready yet.");
                                refreshUi();
                            });
                        })));
    }

    private void buildContactsSection() {
        addSectionHeader("Contact names");
        boolean granted = RamblerContactsRuntime.hasContactsPermission(this);
        int seededCount = RamblerContactsRuntime.readSeededCount(this);
        addRow(
                "Contacts permission",
                granted
                        ? "Granted (" + seededCount + " contact names synced so far)."
                        : "Not granted. Tap Sync now to grant Contacts permission for Gboard.",
                granted ? "Granted" : "Off",
                false,
                null);
        addRow(
                "Sync contact names now",
                "Adds up to 1,000 contact names to Rambler's voice dictionary (new contacts only).",
                "Sync",
                true,
                () -> {
                    if (!RamblerContactsRuntime.hasContactsPermission(this)) {
                        requestPermissions(
                                new String[]{Manifest.permission.READ_CONTACTS},
                                REQ_CONTACTS_PERMISSION);
                        return;
                    }
                    syncContactsNow();
                });
        addRow(
                "Reset sync history",
                "Clears the local record of already-seeded contacts so all contacts can be re-added.",
                "Reset",
                true,
                () -> showConfirmDialog(
                        "Reset contact sync history?",
                        "Existing words in the dictionary stay, and removed contacts will be eligible to sync again.",
                        () -> {
                            RamblerContactsRuntime.resetSeededMemory(this);
                            showToast("Contact sync history cleared.");
                            refreshUi();
                        }));
    }

    private void buildDictationBehaviorSection() {
        addSectionHeader("Dictation behavior");
        if (RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
            addToggleRow(
                    "Append-only dictation",
                    "Sends 0 surrounding text when nothing is selected so Rambler inserts purely at the cursor (with clean spacing) instead of rewriting earlier words. Selecting text still allows spoken edits.",
                    RamblerEnhancementsSettings.readAppendOnly(this),
                    checked -> {
                        RamblerEnhancementsSettings.writeAppendOnly(this, checked);
                        refreshUi();
                    });
            addToggleRow(
                    "Cursor-aware editing",
                    "Sends text after the cursor too and enables selected-text-only spoken edits.",
                    RamblerEnhancementsSettings.readCursorEditingEnabled(this),
                    checked -> {
                        RamblerEnhancementsSettings.writeCursorEditingEnabled(this, checked);
                        refreshUi();
                    });
            addToggleRow(
                    "Re-select after edit",
                    "Keep edited text highlighted for chained voice edits (off by default so follow-up dictations append cleanly instead of rewriting the previous edit).",
                    RamblerEnhancementsSettings.readReselectAfterEditEnabled(this),
                    checked -> {
                        RamblerEnhancementsSettings.writeReselectAfterEditEnabled(this, checked);
                        refreshUi();
                    });
        }
        if (RamblerDynamicConfig.FEATURE_APP_CONTEXT) {
            addToggleRow(
                    "App context",
                    "Shares the active app name, input type, and field hint so Rambler matches the tone and formatting of the app.",
                    RamblerEnhancementsSettings.readAppContextEnabled(this),
                    checked -> {
                        RamblerEnhancementsSettings.writeAppContextEnabled(this, checked);
                        refreshUi();
                    });
        }
    }

    private void syncContactsNow() {
        RamblerContactsRuntime.syncNowAsync(this, result -> mainHandler.post(() -> {
            switch (result.getStatus()) {
                case ADDED:
                    showToast("Synced " + result.getAddedCount() + " contact names.");
                    break;
                case UP_TO_DATE:
                    showToast("Contact names are already up to date.");
                    break;
                case NO_PERMISSION:
                    showToast("Contacts permission not granted.");
                    break;
                case STORE_NOT_READY:
                    showToast("Rambler dictionary isn't ready yet. Dictate once and try again.");
                    break;
                case BUSY:
                    showToast("Contact sync is already running.");
                    break;
                default:
                    showToast("Contact sync failed.");
                    break;
            }
            refreshUi();
        }));
    }

    private void showRemoveWordDialog() {
        List<String> words = RamblerMuseStatsBridge.readWords(this);
        if (words == null) {
            showToast("Dictionary not ready yet. Dictate once first.");
            return;
        }
        if (words.isEmpty()) {
            showToast("No words stored to remove.");
            return;
        }
        List<String> sorted = new ArrayList<String>(words);
        Collections.sort(sorted, Comparator.comparing(String::toLowerCase));
        CharSequence[] items = sorted.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle("Remove word")
                .setItems(items, (ignored, which) -> {
                    String target = sorted.get(which);
                    runAsync(() -> {
                        boolean ok = RamblerMuseStatsBridge.removeWord(this, target);
                        mainHandler.post(() -> {
                            showToast(ok
                                    ? "Removed \"" + target + "\"."
                                    : "Could not remove word.");
                            refreshUi();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openColorDialog(
            String title,
            String currentColor,
            String currentSelection,
            String customColor,
            RamblerWaveformSettings.SettingsSnapshot snapshot,
            boolean isProcessingSlot) {
        String[] summaries = {
                "Adaptive Hex palette",
                "System accent",
                COLOR_VALUES[2], COLOR_VALUES[3], COLOR_VALUES[4], COLOR_VALUES[5],
                COLOR_VALUES[6], COLOR_VALUES[7], COLOR_VALUES[8], COLOR_VALUES[9],
                customColor == null ? "" : customColor
        };
        String[] labels = isProcessingSlot ? PROCESSING_COLOR_LABELS : LISTENING_COLOR_LABELS;
        RamblerWaveformDialogs.showPaletteDialog(
                this,
                title,
                labels,
                summaries,
                COLOR_VALUES,
                currentSelection,
                snapshot,
                isProcessingSlot,
                () -> openCustomColorDialog(
                        title, currentColor, customColor, snapshot, isProcessingSlot),
                selected -> {
                    if (isProcessingSlot) {
                        RamblerWaveformSettings.writeProcessingColor(this, selected);
                    } else {
                        RamblerWaveformSettings.writeListeningColor(this, selected);
                    }
                    refreshUi();
                });
    }

    private void openCustomColorDialog(
            String title,
            String currentColor,
            String customColor,
            RamblerWaveformSettings.SettingsSnapshot snapshot,
            boolean isProcessingSlot) {
        String fallback = isProcessingSlot
                ? RamblerWaveformSettings.DEFAULT_CUSTOM_PROCESSING_HEX
                : RamblerWaveformSettings.DEFAULT_CUSTOM_LISTENING_HEX;
        String initial = customColor != null
                ? customColor
                : (RamblerWaveformSettings.tryNormalizeColorHex(currentColor) != null
                        ? currentColor
                        : fallback);
        RamblerWaveformDialogs.showColorPickerDialog(
                this,
                title,
                initial,
                snapshot,
                isProcessingSlot,
                selectedHex -> {
                    if (isProcessingSlot) {
                        RamblerWaveformSettings.writeProcessingCustomColor(this, selectedHex);
                    } else {
                        RamblerWaveformSettings.writeListeningCustomColor(this, selectedHex);
                    }
                    refreshUi();
                });
    }

    private void addSectionHeader(String title) {
        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(pal.accent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = contentContainer.getChildCount() == 0 ? dp(2) : dp(16);
        params.bottomMargin = dp(8);
        contentContainer.addView(header, params);
    }

    private void addRow(
            String title,
            String summary,
            String badgeValue,
            boolean clickable,
            Runnable onClick) {
        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(RamblerWaveformDialogs.tileBackground(this, false));
        card.setEnabled(clickable || onClick == null);
        card.setClickable(clickable && onClick != null);
        card.setFocusable(clickable && onClick != null);
        card.setAlpha((clickable || onClick == null) ? 1.0f : 0.55f);
        if (clickable && onClick != null) {
            card.setOnClickListener(v -> onClick.run());
        }

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(pal.primary);
        topLine.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        if (badgeValue != null && !badgeValue.isEmpty()) {
            TextView valueView = new TextView(this);
            valueView.setText(badgeValue);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            valueView.setTypeface(Typeface.DEFAULT_BOLD);
            valueView.setTextColor((clickable || onClick == null) ? pal.accent : pal.secondary);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            valueParams.leftMargin = dp(10);
            topLine.addView(valueView, valueParams);
        }
        card.addView(topLine);

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryView.setTextColor(pal.secondary);
        LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumParams.topMargin = dp(4);
        card.addView(summaryView, sumParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(9);
        contentContainer.addView(card, cardParams);
    }

    private interface BooleanConsumer {
        void accept(boolean value);
    }

    private void addToggleRow(
            String title,
            String summary,
            boolean initialChecked,
            BooleanConsumer onToggle) {
        RamblerWaveformDialogs.Palette pal = RamblerWaveformDialogs.palette(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(RamblerWaveformDialogs.tileBackground(this, false));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout topLine = new LinearLayout(this);
        topLine.setOrientation(LinearLayout.HORIZONTAL);
        topLine.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(pal.primary);
        topLine.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Switch toggle = new Switch(this);
        toggle.setChecked(initialChecked);
        toggle.setOnCheckedChangeListener((ignored, checked) -> onToggle.accept(checked));
        topLine.addView(toggle);
        card.addView(topLine);

        card.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        summaryView.setTextColor(pal.secondary);
        LinearLayout.LayoutParams sumParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sumParams.topMargin = dp(4);
        card.addView(summaryView, sumParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(9);
        contentContainer.addView(card, cardParams);
    }

    private void showTextInputDialog(
            String title,
            String hint,
            String initialValue,
            RamblerWaveformDialogs.SelectionConsumer onSave) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(12), dp(20), dp(4));

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(initialValue == null ? "" : initialValue);
        input.setSelection(input.getText().length());
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(8);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("Save", (ignored, which) ->
                        onSave.accept(input.getText() == null ? "" : input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfirmDialog(String title, String message, Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Confirm", (ignored, which) -> onConfirm.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String formatDictionaryStatus(List<String> words) {
        if (words == null) {
            return "Store not ready yet — dictate once with Rambler to initialize MUSE_STATS.";
        }
        if (words.isEmpty()) {
            return "0 words stored.";
        }
        StringBuilder preview = new StringBuilder();
        int shown = Math.min(words.size(), PREVIEW_WORD_LIMIT);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                preview.append(", ");
            }
            preview.append(words.get(i));
        }
        if (words.size() > shown) {
            preview.append(" (+" + (words.size() - shown) + " more)");
        }
        return words.size() + " word(s): " + preview;
    }

    private String readTextFromUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable err) {
            Log.w(TAG, "Failed to read imported file", err);
            return "";
        }
    }

    private boolean writeTextToUri(Uri uri, String content) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) {
                return false;
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Throwable err) {
            Log.w(TAG, "Failed to export file", err);
            return false;
        }
    }

    private void runAsync(Runnable task) {
        Thread worker = new Thread(task, "rambler-enhancements-ui-io");
        worker.setDaemon(true);
        worker.start();
    }

    private void toastOnMain(String message) {
        mainHandler.post(() -> showToast(message));
    }

    private void showToast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // Safe no-op in headless tests.
        }
    }

    private static String displayListening(String value) {
        for (int i = 0; i < LISTENING_VALUES.length; i++) {
            if (LISTENING_VALUES[i].equals(value)) {
                return LISTENING_LABELS[i];
            }
        }
        return LISTENING_LABELS[0];
    }

    private static String displayProcessing(String value) {
        for (int i = 0; i < PROCESSING_VALUES.length; i++) {
            if (PROCESSING_VALUES[i].equals(value)) {
                return PROCESSING_LABELS[i];
            }
        }
        return PROCESSING_LABELS[0];
    }

    private static String displayColor(String value, boolean isProcessingSlot) {
        String[] labels = isProcessingSlot ? PROCESSING_COLOR_LABELS : LISTENING_COLOR_LABELS;
        if (RamblerWaveformSettings.isHexDefault(value)) {
            return labels[0];
        }
        if (RamblerWaveformSettings.isThemeAuto(value)) {
            return labels[1];
        }
        for (int i = 2; i < COLOR_VALUES.length - 1; i++) {
            if (COLOR_VALUES[i].equals(value)) {
                return labels[i];
            }
        }
        return value;
    }

    private static String displayTuning(RamblerWaveformSettings.SettingsSnapshot snapshot) {
        if (snapshot == null || snapshot.isDefaultTuning()) {
            return "Default";
        }
        if (snapshot.isIosHaloGlow()
                && Math.abs(snapshot.getDotSpacingDp()
                        - RamblerWaveformSettings.DEFAULT_DOT_SPACING_DP) < 0.02f
                && Math.abs(snapshot.getGlowIntensity()
                        - RamblerWaveformSettings.DEFAULT_GLOW_INTENSITY) < 0.02f
                && Math.abs(snapshot.getVoiceResponse()
                        - RamblerWaveformSettings.DEFAULT_VOICE_RESPONSE) < 0.02f) {
            return "iOS halo";
        }
        return "Custom";
    }

    private int dp(int value) {
        return RamblerWaveformDialogs.dp(this, value);
    }
}
