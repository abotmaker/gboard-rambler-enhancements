package dev.abotmaker.ramblerenhancements;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public final class RamblerEnhancementsTest {
    @After
    public void tearDown() {
        RamblerWaveformRuntime.clearForTesting();
        RamblerFlagRuntime.clearForTesting();
        RamblerPreferenceLauncher.clearForTesting();
        RamblerCustomInstructionRules.clearForTesting();
        RamblerMuseStatsBridge.setBackendForTesting(null);
        RamblerDynamicConfig.AGENTIC_CONTEXT_FIELD = "b";
        RamblerDynamicConfig.AGENTIC_EDITOR_INFO_FIELD = "h";
        RamblerDynamicConfig.FEATURE_WAVEFORM = false;
        RamblerDynamicConfig.FEATURE_CUSTOM_INSTRUCTIONS = false;
        RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY = false;
        RamblerDynamicConfig.FEATURE_CONTACT_NAMES = false;
        RamblerDynamicConfig.FEATURE_CURSOR_EDITING = false;
        RamblerDynamicConfig.FEATURE_APP_CONTEXT = false;
        RamblerDynamicConfig.FLAG_NAME_FIELD = "a";
        RamblerDynamicConfig.FLAG_RECOMPUTE_METHOD = "x";
        RamblerDynamicConfig.FLAG_NOTIFY_METHOD = "t";
        RamblerDynamicConfig.DRAWABLE_SHADER_FIELD = "e";
        RamblerDynamicConfig.DRAWABLE_CORNER_RADIUS_FIELD = "h";
        RamblerDynamicConfig.SHADER_PROCESSING_FIELD = "l";
        RamblerDynamicConfig.SHADER_COLLAPSING_FIELD = "i";
        RamblerDynamicConfig.SHADER_EXIT_FADE_FIELD = "j";
        RamblerDynamicConfig.SHADER_GLOBAL_ALPHA_FIELD = "o";
    }

    @Test
    public void dotScopeAndAndroidWaveSimulateAndRenderCleanly() {
        HexDotScopeSimulation sim = new HexDotScopeSimulation();
        sim.configureForBounds(600, 120, 2.0f, 3.0f);
        for (int i = 0; i < 60; i++) {
            sim.advance(1.0f / 60.0f, HexDotScopeSimulation.Stage.RECORDING, true,
                    HexDotScopeSimulation.loudnessFromSpeechLevel(80), false);
        }
        HexDotScopeRenderer dotRenderer = new HexDotScopeRenderer();
        android.graphics.Rect bounds = new android.graphics.Rect(0, 0, 600, 120);
        RamblerWaveformSettings.SettingsSnapshot snapshot =
                new RamblerWaveformSettings.SettingsSnapshot(
                        RamblerWaveformSettings.LISTENING_HEX_DOT_SCOPE,
                        RamblerWaveformSettings.PROCESSING_SWEEP,
                        "#34D399",
                        "#A78BFA",
                        RamblerWaveformSettings.GLOW_LOOK_IOS_HALO,
                        3.0f,
                        1.4f,
                        1.2f);
        RecordingCanvas dotCanvas = new RecordingCanvas();
        dotRenderer.draw(dotCanvas, bounds, 2.0f, 16.0f, 24.0f, 24.0f, 20.0f,
                true, Color.parseColor("#4285F4"), snapshot, sim, 1.0f);
        assertTrue(dotCanvas.clipCount > 0);
        assertTrue(dotCanvas.circleCount > 100);
        assertTrue(dotCanvas.greenDominantDots > 20);

        HexAndroidWaveSimulation wave = new HexAndroidWaveSimulation();
        for (int i = 0; i < 40; i++) {
            wave.advance(1.0f / 60.0f, 0.85f, 1.2f, false);
        }
        HexAndroidWaveRenderer waveRenderer = new HexAndroidWaveRenderer();
        RecordingCanvas waveCanvas = new RecordingCanvas();
        waveRenderer.draw(waveCanvas, bounds, 2.0f, 16.0f, 24.0f, 24.0f, 20.0f,
                true, Color.parseColor("#4285F4"),
                snapshot.withListeningStyle(RamblerWaveformSettings.LISTENING_HEX_ANDROID_WAVE),
                wave, 1.0f);
        assertTrue(waveCanvas.clipCount > 0);
        assertTrue(waveCanvas.circleCount > 120);
        assertTrue(waveCanvas.greenDominantDots > 40);
    }

    @Test
    public void waveformRuntimeHonorsDynamicFieldConfig() {
        RamblerDynamicConfig.DRAWABLE_SHADER_FIELD = "renamedShader";
        RamblerDynamicConfig.DRAWABLE_CORNER_RADIUS_FIELD = "renamedRadius";
        RamblerDynamicConfig.SHADER_PROCESSING_FIELD = "renamedProc";
        RamblerDynamicConfig.SHADER_COLLAPSING_FIELD = "renamedCollapse";
        RamblerDynamicConfig.SHADER_EXIT_FADE_FIELD = "renamedFade";
        RamblerDynamicConfig.SHADER_GLOBAL_ALPHA_FIELD = "renamedAlpha";

        Context context = RuntimeEnvironment.getApplication();
        FrameLayout header = new FrameLayout(context);
        header.layout(0, 0, 1080, 144);
        RenamedRamblerDrawable drawable = new RenamedRamblerDrawable();
        drawable.setBounds(0, 0, 1080, 144);

        RamblerWaveformRuntime.onAttach(header, drawable);
        RamblerWaveformRuntime.onSpeechLevel(75, drawable, header);
        RamblerWaveformRuntime.setSnapshotOverrideForTesting(
                new RamblerWaveformSettings.SettingsSnapshot(
                        RamblerWaveformSettings.LISTENING_HEX_ANDROID_WAVE,
                        RamblerWaveformSettings.PROCESSING_PULSE,
                        "#FF614F",
                        "#22D3EE"));

        Canvas canvas = new Canvas(Bitmap.createBitmap(1080, 144, Bitmap.Config.ARGB_8888));
        assertTrue(RamblerWaveformRuntime.maybeDraw(drawable, canvas));
        drawable.renamedShader.renamedProc = true;
        assertTrue(RamblerWaveformRuntime.maybeDraw(drawable, canvas));
        drawable.renamedShader.renamedCollapse = true;
        assertTrue(RamblerWaveformRuntime.maybeDraw(drawable, canvas));
        drawable.renamedShader.renamedFade = true;
        drawable.renamedShader.renamedAlpha = 0.2f;
        assertTrue(RamblerWaveformRuntime.maybeDraw(drawable, canvas));
    }

    @Test
    public void flagRuntimeGatesBySelectedFeatureAndRefreshesLive() {
        FakeOkiFlag surroundingLimit = new FakeOkiFlag(
                RamblerFlagRuntime.SURROUNDING_TEXT_LIMIT_FLAG, Long.valueOf(1000L));
        FakeOkiFlag customInstr = new FakeOkiFlag(
                RamblerFlagRuntime.CUSTOM_INSTRUCTION_FLAG, Boolean.FALSE);
        FakeOkiFlag appContext = new FakeOkiFlag(
                RamblerFlagRuntime.APP_CONTEXT_FLAG, Boolean.FALSE);

        // When no features are selected, everything passes through unchanged.
        assertFalse(surroundingLimit.recompute());
        assertEquals(Long.valueOf(1000L), surroundingLimit.resolved);
        assertEquals(Boolean.FALSE, RamblerFlagRuntime.applyResolvedFlag(customInstr, Boolean.FALSE));

        // Enable Cursor Editing + Custom Instructions + App Context.
        RamblerFlagRuntime.clearForTesting();
        RamblerDynamicConfig.FEATURE_CURSOR_EDITING = true;
        RamblerDynamicConfig.FEATURE_CUSTOM_INSTRUCTIONS = true;
        RamblerDynamicConfig.FEATURE_APP_CONTEXT = true;
        RamblerDynamicConfig.FLAG_NAME_FIELD = "flagName";
        RamblerDynamicConfig.FLAG_RECOMPUTE_METHOD = "recompute";
        RamblerDynamicConfig.FLAG_NOTIFY_METHOD = "notifyListeners";

        assertEquals(Boolean.TRUE, RamblerFlagRuntime.applyResolvedFlag(customInstr, Boolean.FALSE));
        assertEquals(Boolean.TRUE, RamblerFlagRuntime.applyResolvedFlag(appContext, Boolean.FALSE));

        // Initial compute with append-only OFF keeps 1000L and caches the flag instance.
        RamblerFlagRuntime.setAppendOnlyOverrideForTesting(false);
        surroundingLimit.recompute();
        assertEquals(Long.valueOf(1000L), surroundingLimit.resolved);

        // Flip append-only ON live -> refreshLiveFlags recomputes to 0L and notifies listeners!
        RamblerFlagRuntime.setAppendOnlyOverrideForTesting(true);
        RamblerFlagRuntime.refreshLiveFlags();
        assertEquals(Long.valueOf(0L), surroundingLimit.resolved);
        assertEquals(1, surroundingLimit.notifyCount);

        // Flip append-only OFF live -> refreshLiveFlags restores 1000L and notifies again!
        RamblerFlagRuntime.setAppendOnlyOverrideForTesting(false);
        RamblerFlagRuntime.refreshLiveFlags();
        assertEquals(Long.valueOf(1000L), surroundingLimit.resolved);
        assertEquals(2, surroundingLimit.notifyCount);

        // Verify native SwitchPreferenceCompat writes to default SharedPreferences live-refresh flags!
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences defaultPrefs =
                RamblerEnhancementsSettings.defaultPreferencesOrNull(context);
        defaultPrefs.edit().clear().commit();
        RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY = true;
        RamblerFlagRuntime.setAppendOnlyOverrideForTesting(null);
        RamblerFlagRuntime.seedAndRefreshIfNeeded(context);

        FakeOkiFlag sendWords = new FakeOkiFlag(
                RamblerFlagRuntime.SEND_WORDS_FLAG, Boolean.FALSE);
        sendWords.recompute();
        assertEquals(Boolean.TRUE, sendWords.resolved);

        // Flipping SwitchPreferenceCompat writes directly to default SharedPreferences -> live listener fires!
        defaultPrefs.edit()
                .putBoolean(RamblerEnhancementsSettings.PREF_KEY_USE_DICTIONARY_WORDS, false)
                .commit();
        assertEquals(Boolean.FALSE, sendWords.resolved);
        assertEquals(1, sendWords.notifyCount);

        defaultPrefs.edit()
                .putBoolean(RamblerEnhancementsSettings.PREF_KEY_APPEND_ONLY, true)
                .commit();
        assertEquals(Long.valueOf(0L), surroundingLimit.resolved);
        assertTrue(surroundingLimit.notifyCount >= 3);
    }

    @Test
    public void appendSpacingPreservesCleanBoundaries() {
        assertEquals(" world", RamblerFlagRuntime.adjustAppendSpacing("Hello", "", "world"));
        assertEquals("world", RamblerFlagRuntime.adjustAppendSpacing("Hello ", "", "world"));
        assertEquals(",", RamblerFlagRuntime.adjustAppendSpacing("Hello", "", ","));
        assertEquals("world", RamblerFlagRuntime.adjustAppendSpacing("(", ")", "world"));
        assertEquals(" world ", RamblerFlagRuntime.adjustAppendSpacing("Hello", "there", "world"));
        assertEquals("世界", RamblerFlagRuntime.adjustAppendSpacing("你好", "", "世界"));
    }

    @Test
    public void museStatsWordParsingAndContactsFilteringWork() {
        LinkedHashSet<String> parsed = RamblerMuseStatsBridge.parseWordList(
                "  Morphe, GboardWu;\nrambler\nMORPHE\n  ");
        assertEquals(Arrays.asList("Morphe", "GboardWu", "rambler"),
                new java.util.ArrayList<String>(parsed));
        assertEquals("Morphe\nGboardWu\n",
                RamblerMuseStatsBridge.formatExportText(Arrays.asList("Morphe", "GboardWu")));

        byte[] encoded = RamblerMuseStatsBridge.encodeWordProto("Hex");
        assertEquals(5, encoded.length);
        assertEquals(0x0A, encoded[0]);
        assertEquals(3, encoded[1]);

        LinkedHashSet<String> contacts = RamblerContactsRuntime.selectNewNames(
                Arrays.asList("Alice Smith", "alice smith", "bob@example.com", "+1-555-0100", "Dr. O'Connor"),
                Collections.singleton("Alice Smith"),
                10);
        assertEquals(List.of("Dr. O'Connor"), new java.util.ArrayList<String>(contacts));
    }

    @Test
    public void waveformAndBehaviorSettingsPersistCleanly() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences wPrefs = RamblerWaveformSettings.preferencesOrNull(context);
        wPrefs.edit().clear().commit();
        SharedPreferences ePrefs = RamblerEnhancementsSettings.preferencesOrNull(context);
        ePrefs.edit().clear().commit();
        SharedPreferences defaultPrefs =
                RamblerEnhancementsSettings.defaultPreferencesOrNull(context);
        defaultPrefs.edit().clear().commit();

        assertEquals(RamblerWaveformSettings.LISTENING_HEX_DOT_SCOPE,
                RamblerWaveformSettings.snapshot(context).getListeningStyle());
        assertTrue(RamblerWaveformSettings.writeListeningStyle(
                context, RamblerWaveformSettings.LISTENING_HEX_ANDROID_WAVE));
        assertTrue(RamblerWaveformSettings.writeProcessingStyle(
                context, RamblerWaveformSettings.PROCESSING_SWEEP));
        assertTrue(RamblerWaveformSettings.writeListeningCustomColor(context, "34d399"));
        assertTrue(RamblerWaveformSettings.writeTuning(
                context, RamblerWaveformSettings.GLOW_LOOK_IOS_HALO, 2.6f, 1.8f, 1.5f));

        RamblerWaveformSettings.SettingsSnapshot snap = RamblerWaveformSettings.snapshot(context);
        assertTrue(snap.isAndroidWaveListening());
        assertEquals(HexDotScopeSimulation.ProcessingMode.SWEEP, snap.getProcessingMode());
        assertEquals("#34D399", snap.getListeningColorValue());
        assertTrue(snap.isIosHaloGlow());
        assertNull(RamblerWaveformSettings.tryNormalizeColorHex("bad"));

        assertTrue(RamblerEnhancementsSettings.readUseDictionaryWords(context));
        assertTrue(RamblerEnhancementsSettings.writeUseDictionaryWords(context, false));
        assertFalse(RamblerEnhancementsSettings.readUseDictionaryWords(context));
        assertFalse(defaultPrefs.getBoolean(
                RamblerEnhancementsSettings.PREF_KEY_USE_DICTIONARY_WORDS, true));

        assertFalse(RamblerEnhancementsSettings.readAppendOnly(context));
        assertTrue(RamblerEnhancementsSettings.writeAppendOnly(context, true));
        assertTrue(RamblerEnhancementsSettings.readAppendOnly(context));
        assertTrue(RamblerEnhancementsSettings.writeAppContextEnabled(context, false));
        assertFalse(RamblerEnhancementsSettings.readAppContextEnabled(context));
    }

    @Test
    public void preferenceLauncherConvertsImplicitActionToExplicitOwnPackageIntent() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.Intent implicit = new android.content.Intent(
                RamblerPreferenceLauncher.ACTION_OPEN_SETTINGS);
        implicit.putExtra(
                RamblerEnhancementsActivity.EXTRA_SECTION,
                RamblerEnhancementsActivity.SECTION_WAVEFORM);

        android.content.Intent explicit =
                RamblerPreferenceLauncher.buildExplicitIntent(context, implicit);
        assertEquals(context.getPackageName(), explicit.getComponent().getPackageName());
        assertEquals(
                RamblerEnhancementsActivity.class.getName(),
                explicit.getComponent().getClassName());
        assertEquals(
                RamblerEnhancementsActivity.SECTION_WAVEFORM,
                explicit.getStringExtra(RamblerEnhancementsActivity.EXTRA_SECTION));

        // Unrelated preferences pass through untouched
        FakePreference unrelated = new FakePreference(
                context, new android.content.Intent("android.intent.action.VIEW"));
        assertFalse(RamblerPreferenceLauncher.maybeLaunch(unrelated));
        assertFalse(RamblerPreferenceLauncher.maybeLaunch(new FakePreference(context, null)));
    }

    @Test
    public void gboardProtoPreferenceStoreDrivesLiveTogglesAndInstantFlagRefresh() {
        Context context = RuntimeEnvironment.getApplication();
        String originalPrefsClass = RamblerDynamicConfig.PREFS_CLASS;
        String originalGetInstance = RamblerDynamicConfig.PREFS_GET_INSTANCE_METHOD;
        try {
            FakeGboardPrefs.reset(context);
            RamblerDynamicConfig.FEATURE_CURSOR_EDITING = true;
            RamblerDynamicConfig.FEATURE_APP_CONTEXT = true;
            RamblerDynamicConfig.FLAG_NAME_FIELD = "flagName";
            RamblerDynamicConfig.FLAG_RECOMPUTE_METHOD = "recompute";
            RamblerDynamicConfig.FLAG_NOTIFY_METHOD = "notifyListeners";
            RamblerDynamicConfig.PREFS_CLASS = FakeGboardPrefs.class.getName();
            RamblerDynamicConfig.PREFS_GET_INSTANCE_METHOD = "getInstance";
            RamblerFlagRuntime.clearForTesting();

            // Simulate user flipping native switches in setting_jetson_voice.xml (written to qvt,
            // NOT to Android's <pkg>_preferences.xml).
            FakeGboardPrefs.backing.edit()
                    .putBoolean(RamblerEnhancementsSettings.PREF_KEY_APPEND_ONLY, true)
                    .putBoolean(RamblerEnhancementsSettings.PREF_KEY_APP_CONTEXT_ENABLED, false)
                    .commit();

            FakeOkiFlag surroundingLimit = new FakeOkiFlag(
                    RamblerFlagRuntime.SURROUNDING_TEXT_LIMIT_FLAG, Long.valueOf(2000L));
            FakeOkiFlag reselectAfterEdit = new FakeOkiFlag(
                    RamblerFlagRuntime.RESELECT_AFTER_EDIT_FLAG, Boolean.FALSE);
            FakeOkiFlag appContext = new FakeOkiFlag(
                    RamblerFlagRuntime.APP_CONTEXT_FLAG, Boolean.FALSE);

            RamblerFlagRuntime.seedAndRefreshIfNeeded(context);
            assertTrue(surroundingLimit.recompute());
            assertEquals(Long.valueOf(0L), surroundingLimit.resolved);
            assertFalse(reselectAfterEdit.recompute());
            assertEquals(Boolean.FALSE, reselectAfterEdit.resolved);
            assertFalse(appContext.recompute());
            assertEquals(Boolean.FALSE, appContext.resolved);

            // Live flip inside Gboard's native qvt store triggers immediate flag refresh!
            FakeGboardPrefs.instance.putBoolean(
                    RamblerEnhancementsSettings.PREF_KEY_RESELECT_AFTER_EDIT, true);
            assertEquals(Boolean.TRUE, reselectAfterEdit.resolved);
            assertEquals(1, reselectAfterEdit.notifyCount);

            FakeGboardPrefs.instance.putBoolean(
                    RamblerEnhancementsSettings.PREF_KEY_RESELECT_AFTER_EDIT, false);
            assertEquals(Boolean.FALSE, reselectAfterEdit.resolved);
            assertEquals(2, reselectAfterEdit.notifyCount);

            FakeGboardPrefs.instance.putBoolean(
                    RamblerEnhancementsSettings.PREF_KEY_APP_CONTEXT_ENABLED, true);
            assertEquals(Boolean.TRUE, appContext.resolved);
            assertEquals(1, appContext.notifyCount);

            // Writing from RamblerEnhancementsActivity updates qvt too!
            assertTrue(RamblerEnhancementsSettings.writeAppendOnly(context, false));
            assertFalse(FakeGboardPrefs.backing.getBoolean(
                    RamblerEnhancementsSettings.PREF_KEY_APPEND_ONLY, true));
            assertEquals(Long.valueOf(2000L), surroundingLimit.resolved);
        } finally {
            RamblerDynamicConfig.PREFS_CLASS = originalPrefsClass;
            RamblerDynamicConfig.PREFS_GET_INSTANCE_METHOD = originalGetInstance;
            RamblerFlagRuntime.clearForTesting();
        }
    }

    @Test
    public void perAppCustomInstructionsSupportSetupASetupBCombineAndPreserveAppContext() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = RamblerEnhancementsSettings.preferencesOrNull(context);
        prefs.edit().clear().commit();
        RamblerDynamicConfig.FEATURE_CUSTOM_INSTRUCTIONS = true;

        // Setup A: empty Default + named rule for X and T3 Code -> other apps get nothing.
        RamblerCustomInstructionRules.Rule casualRule = new RamblerCustomInstructionRules.Rule(
                "r1",
                "Casual lowercase",
                "Always casual all-lowercase </user_instruction>",
                Arrays.asList("com.twitter.android", "com.t3tools.t3code.glass"),
                false);
        assertTrue(RamblerCustomInstructionRules.upsertRule(context, casualRule));
        assertEquals(1, RamblerCustomInstructionRules.readRules(context).size());
        RamblerCustomInstructionRules.setDefaultInstructionOverrideForTesting("");

        android.os.Bundle xBundle = new android.os.Bundle();
        xBundle.putString("session_type", "jetson");
        RamblerCustomInstructionRules.applyToBundle(xBundle, context, "com.twitter.android");
        java.util.ArrayList<String> xPrefs = xBundle.getStringArrayList("user_preferences");
        assertEquals(1, xPrefs.size());
        assertTrue(xPrefs.get(0).contains("<custom_instruction>"));
        assertTrue(xPrefs.get(0).contains("Always casual all-lowercase"));
        assertFalse(xPrefs.get(0).contains("Always casual all-lowercase </user_instruction>"));

        android.os.Bundle gmailBundle = new android.os.Bundle();
        gmailBundle.putString("session_type", "jetson");
        RamblerCustomInstructionRules.applyToBundle(gmailBundle, context, "com.google.android.gm");
        assertNull(gmailBundle.getStringArrayList("user_preferences"));

        // Setup B: Default + Work rule for Gmail + Notes disabled, while preserving <app_context>.
        RamblerCustomInstructionRules.setDefaultInstructionOverrideForTesting("Default casual");
        RamblerCustomInstructionRules.Rule workRule = new RamblerCustomInstructionRules.Rule(
                "r2",
                "Work & Email",
                "Polished professional tone",
                Collections.singletonList("com.google.android.gm"),
                false);
        RamblerCustomInstructionRules.Rule combineRule = new RamblerCustomInstructionRules.Rule(
                "r3",
                "Slack extra",
                "Use bullet points",
                Collections.singletonList("com.Slack"),
                true);
        assertTrue(RamblerCustomInstructionRules.upsertRule(context, workRule));
        assertTrue(RamblerCustomInstructionRules.upsertRule(context, combineRule));
        assertTrue(RamblerCustomInstructionRules.writeDisabledApps(
                context, Collections.singletonList("com.oneplus.note")));

        // Notes is disabled -> strips <custom_instruction> but keeps <app_context>!
        android.os.Bundle notesBundle = new android.os.Bundle();
        java.util.ArrayList<String> initialNotesPrefs = new java.util.ArrayList<String>(
                Arrays.asList("<app_context>\nApp Name: Notes\n</app_context>",
                        RamblerCustomInstructionRules.wrapCustomInstruction("Default casual")));
        notesBundle.putStringArrayList("user_preferences", initialNotesPrefs);
        RamblerCustomInstructionRules.applyToBundle(notesBundle, context, "com.oneplus.note");
        java.util.ArrayList<String> afterNotes = notesBundle.getStringArrayList("user_preferences");
        assertEquals(1, afterNotes.size());
        assertTrue(afterNotes.get(0).contains("<app_context>"));

        // Slack combines Default + rule.
        android.os.Bundle slackBundle = new android.os.Bundle();
        RamblerCustomInstructionRules.applyToBundle(slackBundle, context, "com.Slack");
        String slackInstr = slackBundle.getStringArrayList("user_preferences").get(0);
        assertTrue(slackInstr.contains("Default casual\nUse bullet points"));

        // End-to-end reflective captureSessionContext + applyPerAppCustomInstruction hook test.
        RamblerDynamicConfig.AGENTIC_CONTEXT_FIELD = "ctx";
        RamblerDynamicConfig.AGENTIC_EDITOR_INFO_FIELD = "editor";
        android.view.inputmethod.EditorInfo gmailEditor = new android.view.inputmethod.EditorInfo();
        gmailEditor.packageName = "com.google.android.gm";
        FakeAgenticExtension fakeExt = new FakeAgenticExtension(context, gmailEditor);
        android.os.Bundle hookedBundle = new android.os.Bundle();
        RamblerCustomInstructionRules.captureSessionContext(fakeExt);
        RamblerCustomInstructionRules.applyPerAppCustomInstruction(hookedBundle);
        String hookedInstr = hookedBundle.getStringArrayList("user_preferences").get(0);
        assertTrue(hookedInstr.contains("Polished professional tone"));
        assertFalse(hookedInstr.contains("Default casual"));
    }

    @SuppressWarnings("unused")
    private static final class FakeAgenticExtension {
        final Context ctx;
        final android.view.inputmethod.EditorInfo editor;

        FakeAgenticExtension(Context ctx, android.view.inputmethod.EditorInfo editor) {
            this.ctx = ctx;
            this.editor = editor;
        }
    }

    @SuppressWarnings("unused")
    public interface FakeGboardPrefListener {
        void onPrefChanged(FakeGboardPrefs prefs, String key);
    }

    @SuppressWarnings("unused")
    public static final class FakeGboardPrefs {
        static FakeGboardPrefs instance;
        static SharedPreferences backing;
        final java.util.function.Supplier<SharedPreferences> e = () -> backing;
        private final java.util.Map<String, java.util.List<FakeGboardPrefListener>> listeners =
                new java.util.HashMap<String, java.util.List<FakeGboardPrefListener>>();

        static void reset(Context context) {
            backing = context.getSharedPreferences("fake_gboard_proto_prefs", Context.MODE_PRIVATE);
            backing.edit().clear().commit();
            instance = new FakeGboardPrefs();
        }

        public static FakeGboardPrefs getInstance(Context context) {
            return instance;
        }

        public void registerListener(FakeGboardPrefListener listener, String key) {
            listeners.computeIfAbsent(key, k -> new java.util.ArrayList<FakeGboardPrefListener>())
                    .add(listener);
        }

        public void putBoolean(String key, boolean value) {
            backing.edit().putBoolean(key, value).commit();
            java.util.List<FakeGboardPrefListener> list = listeners.get(key);
            if (list != null) {
                for (FakeGboardPrefListener l : list) {
                    l.onPrefChanged(this, key);
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static final class FakePreference {
        final Context j;
        final android.content.Intent s;

        FakePreference(Context j, android.content.Intent s) {
            this.j = j;
            this.s = s;
        }
    }

    @SuppressWarnings("unused")
    private static final class FakeOkiFlag {
        final String flagName;
        final Object rawValue;
        Object resolved;
        int notifyCount;

        FakeOkiFlag(String flagName, Object rawValue) {
            this.flagName = flagName;
            this.rawValue = rawValue;
            this.resolved = rawValue;
        }

        boolean recompute() {
            Object next = RamblerFlagRuntime.applyResolvedFlag(this, rawValue);
            boolean changed = resolved == null ? next != null : !resolved.equals(next);
            resolved = next;
            return changed;
        }

        void notifyListeners() {
            notifyCount++;
        }
    }

    private static final class RecordingCanvas extends Canvas {
        int circleCount;
        int clipCount;
        int greenDominantDots;

        @Override
        public boolean clipPath(android.graphics.Path path) {
            clipCount++;
            return true;
        }

        @Override
        public void drawCircle(float cx, float cy, float radius, android.graphics.Paint paint) {
            if (paint == null || radius <= 0.0f) {
                return;
            }
            int color = paint.getColor();
            if (Color.alpha(color) > 0) {
                circleCount++;
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                if (g > r && g > b) {
                    greenDominantDots++;
                }
            }
        }
    }

    @SuppressWarnings("unused")
    private static final class RenamedRamblerDrawable extends Drawable {
        public final RenamedShaderState renamedShader = new RenamedShaderState();
        public float renamedRadius = 32.0f;

        @Override
        public void draw(Canvas canvas) {
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    @SuppressWarnings("unused")
    private static final class RenamedShaderState {
        public boolean renamedProc = false;
        public boolean renamedCollapse = false;
        public boolean renamedFade = false;
        public float renamedAlpha = 1.0f;
    }
}
