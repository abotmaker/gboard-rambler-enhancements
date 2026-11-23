package dev.abotmaker.ramblerenhancements;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conflict-free runtime flag controller invoked from Gboard's internal flag resolution method
 * ({@code oki.x()} in 18.5.4) so that GboardWu's {@code oki.g()} hook remains completely untouched.
 *
 * <p>Only flags belonging to patches selected at Morphe patch time
 * ({@link RamblerDynamicConfig}{@code .FEATURE_*}) are modified. Live user toggles
 * (Append-only dictation, Cursor-aware editing, App context) immediately recompute and notify
 * cached flag instances without needing a Gboard restart.</p>
 */
public final class RamblerFlagRuntime {
    private static final String TAG = "RamblerEnhancements";

    // Custom instructions
    public static final String CUSTOM_INSTRUCTION_FLAG =
            "agentic_dictation_enable_custom_instruction";

    // Personal dictionary (UserFeatureCache MUSE_STATS)
    public static final String SHOW_DICTIONARY_SETTINGS_FLAG = "show_rambler_dict_settings";
    public static final String SHOW_CLEAR_ALL_FLAG = "show_clear_all_corrected_words_settings";
    public static final String SEND_WORDS_FLAG = "enable_user_contact_biasing";
    public static final String FEATURE_CACHE_FLAG = "enable_user_feature_cache";

    // Cursor-aware & append-only editing
    public static final String CURSOR_AWARENESS_FLAG = "jetson_enable_cursor_awareness";
    public static final String SELECTED_TEXT_ONLY_FLAG =
            "jetson_send_only_selected_text_if_present";
    public static final String RESELECT_AFTER_EDIT_FLAG = "jetson_reselect_after_edit";
    public static final String SURROUNDING_TEXT_LIMIT_FLAG =
            "jetson_surrounding_text_length_limit";

    // App context
    public static final String APP_CONTEXT_FLAG = "agentic_dictation_enable_app_info_context";

    private static final Object FLAG_CACHE_LOCK = new Object();
    private static final WeakHashMap<Object, String> LIVE_TOGGLE_FLAGS =
            new WeakHashMap<Object, String>();
    private static final AtomicBoolean CONTEXT_HYDRATED = new AtomicBoolean(false);
    private static final AtomicBoolean PREF_LISTENER_ATTACHED = new AtomicBoolean(false);

    private static volatile Context applicationContext;
    private static volatile FlagHandles cachedHandles;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener livePrefListener;
    private static volatile Boolean useDictionaryWordsOverrideForTesting;
    private static volatile Boolean appendOnlyOverrideForTesting;
    private static volatile Boolean cursorEditingOverrideForTesting;
    private static volatile Boolean appContextOverrideForTesting;

    private RamblerFlagRuntime() {
    }

    public static void seedApplicationContext(Context context) {
        if (context == null) {
            return;
        }
        Context resolved = context;
        try {
            Context app = context.getApplicationContext();
            if (app != null) {
                resolved = app;
            }
        } catch (Throwable ignored) {
            // Keep caller context as fallback.
        }
        applicationContext = resolved;
        attachDefaultPrefsListenerIfNeeded(resolved);
    }

    /**
     * Seeds the application context and performs a one-time flag refresh on first startup once
     * a real Context becomes available (so saved toggles like Append-only take effect even if
     * flags were first computed during early static initialization).
     */
    public static void seedAndRefreshIfNeeded(Context context) {
        if (context == null) {
            return;
        }
        seedApplicationContext(context);
        if (CONTEXT_HYDRATED.compareAndSet(false, true)) {
            refreshLiveFlags();
        }
    }

    private static void attachDefaultPrefsListenerIfNeeded(Context context) {
        if (context == null || PREF_LISTENER_ATTACHED.get()) {
            return;
        }
        try {
            SharedPreferences defaultPrefs =
                    RamblerEnhancementsSettings.defaultPreferencesOrNull(context);
            if (defaultPrefs == null) {
                return;
            }
            if (!PREF_LISTENER_ATTACHED.compareAndSet(false, true)) {
                return;
            }
            SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                if (RamblerEnhancementsSettings.isLiveToggleKey(key)) {
                    refreshLiveFlags();
                }
            };
            livePrefListener = listener;
            defaultPrefs.registerOnSharedPreferenceChangeListener(listener);
        } catch (Throwable ignored) {
            // Best-effort live listener for native SwitchPreferenceCompat toggles.
        }
    }

    static void setUseDictionaryWordsOverrideForTesting(Boolean enabled) {
        useDictionaryWordsOverrideForTesting = enabled;
    }

    static void setAppendOnlyOverrideForTesting(Boolean enabled) {
        appendOnlyOverrideForTesting = enabled;
    }

    static void setCursorEditingOverrideForTesting(Boolean enabled) {
        cursorEditingOverrideForTesting = enabled;
    }

    static void setAppContextOverrideForTesting(Boolean enabled) {
        appContextOverrideForTesting = enabled;
    }

    static void clearForTesting() {
        useDictionaryWordsOverrideForTesting = null;
        appendOnlyOverrideForTesting = null;
        cursorEditingOverrideForTesting = null;
        appContextOverrideForTesting = null;
        applicationContext = null;
        cachedHandles = null;
        livePrefListener = null;
        CONTEXT_HYDRATED.set(false);
        PREF_LISTENER_ATTACHED.set(false);
        synchronized (FLAG_CACHE_LOCK) {
            LIVE_TOGGLE_FLAGS.clear();
        }
    }

    public static boolean isUseDictionaryWordsEnabled() {
        if (!RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY) {
            return false;
        }
        Boolean override = useDictionaryWordsOverrideForTesting;
        if (override != null) {
            return override.booleanValue();
        }
        return RamblerEnhancementsSettings.readUseDictionaryWords(resolveContext());
    }

    public static boolean isAppendOnlyEnabled() {
        if (!RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
            return false;
        }
        Boolean override = appendOnlyOverrideForTesting;
        if (override != null) {
            return override.booleanValue();
        }
        return RamblerEnhancementsSettings.readAppendOnly(resolveContext());
    }

    public static boolean isCursorEditingEnabled() {
        if (!RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
            return false;
        }
        Boolean override = cursorEditingOverrideForTesting;
        if (override != null) {
            return override.booleanValue();
        }
        return RamblerEnhancementsSettings.readCursorEditingEnabled(resolveContext());
    }

    public static boolean isAppContextEnabled() {
        if (!RamblerDynamicConfig.FEATURE_APP_CONTEXT) {
            return false;
        }
        Boolean override = appContextOverrideForTesting;
        if (override != null) {
            return override.booleanValue();
        }
        return RamblerEnhancementsSettings.readAppContextEnabled(resolveContext());
    }

    /**
     * Hook called inside Gboard's internal flag recompute method ({@code oki.x()}) right after
     * layer resolution and before equality comparison / snapshot assignment.
     */
    public static Object applyResolvedFlag(Object flagInstance, Object rawValue) {
        if (flagInstance == null || rawValue == null) {
            return rawValue;
        }
        try {
            String flagName = readFlagName(flagInstance);
            if (flagName == null) {
                return rawValue;
            }
            return applyFlagByName(flagInstance, flagName, rawValue);
        } catch (Throwable throwable) {
            logWarningSafely("Flag override failed", throwable);
            return rawValue;
        }
    }

    static Object applyFlagByName(Object flagInstance, String flagName, Object rawValue) {
        if (flagName == null || rawValue == null) {
            return rawValue;
        }
        switch (flagName) {
            case CUSTOM_INSTRUCTION_FLAG:
                return RamblerDynamicConfig.FEATURE_CUSTOM_INSTRUCTIONS
                        ? enableBoolean(rawValue)
                        : rawValue;

            case SHOW_DICTIONARY_SETTINGS_FLAG:
            case SHOW_CLEAR_ALL_FLAG:
            case FEATURE_CACHE_FLAG:
                return RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY
                        ? enableBoolean(rawValue)
                        : rawValue;

            case SEND_WORDS_FLAG:
                if (!RamblerDynamicConfig.FEATURE_PERSONAL_DICTIONARY) {
                    return rawValue;
                }
                rememberLiveFlag(flagInstance, flagName);
                if (rawValue instanceof Boolean) {
                    return Boolean.valueOf(isUseDictionaryWordsEnabled());
                }
                return rawValue;

            case SURROUNDING_TEXT_LIMIT_FLAG:
                if (!RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
                    return rawValue;
                }
                rememberLiveFlag(flagInstance, flagName);
                if (rawValue instanceof Long && isAppendOnlyEnabled()) {
                    return Long.valueOf(0L);
                }
                return rawValue;

            case CURSOR_AWARENESS_FLAG:
                if (!RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
                    return rawValue;
                }
                rememberLiveFlag(flagInstance, flagName);
                return isCursorEditingEnabled() ? enableBoolean(rawValue) : rawValue;

            case SELECTED_TEXT_ONLY_FLAG:
            case RESELECT_AFTER_EDIT_FLAG:
                if (!RamblerDynamicConfig.FEATURE_CURSOR_EDITING) {
                    return rawValue;
                }
                rememberLiveFlag(flagInstance, flagName);
                return (isCursorEditingEnabled() || isAppendOnlyEnabled())
                        ? enableBoolean(rawValue)
                        : rawValue;

            case APP_CONTEXT_FLAG:
                if (!RamblerDynamicConfig.FEATURE_APP_CONTEXT) {
                    return rawValue;
                }
                rememberLiveFlag(flagInstance, flagName);
                return isAppContextEnabled() ? enableBoolean(rawValue) : rawValue;

            default:
                return rawValue;
        }
    }

    /**
     * Recomputes all cached live-toggleable Gboard flag instances and fires Gboard's internal
     * flag-change listeners when a value changed.
     */
    public static void refreshLiveFlags() {
        List<Object> flags;
        synchronized (FLAG_CACHE_LOCK) {
            if (LIVE_TOGGLE_FLAGS.isEmpty()) {
                return;
            }
            flags = new ArrayList<Object>(LIVE_TOGGLE_FLAGS.keySet());
        }
        for (Object flag : flags) {
            if (flag == null) {
                continue;
            }
            try {
                FlagHandles handles = resolveHandles(flag.getClass());
                if (handles == null || handles.recomputeMethod == null) {
                    continue;
                }
                boolean changed;
                synchronized (flag) {
                    Object res = handles.recomputeMethod.invoke(flag);
                    changed = Boolean.TRUE.equals(res);
                }
                if (changed && handles.notifyMethod != null) {
                    handles.notifyMethod.invoke(flag);
                }
            } catch (Throwable throwable) {
                logWarningSafely("Live flag refresh failed", throwable);
            }
        }
    }

    /**
     * Preserves clean word/sentence spacing when Rambler inserts text in append-only mode
     * (where the server received 0 characters of surrounding text).
     */
    public static CharSequence adjustAppendSpacing(
            CharSequence before, CharSequence after, CharSequence text) {
        if (text == null || text.length() == 0) {
            return text;
        }
        boolean prependSpace = false;
        if (before != null && before.length() > 0) {
            char prev = before.charAt(before.length() - 1);
            char first = text.charAt(0);
            if (!Character.isWhitespace(prev)
                    && !isOpeningDelimiter(prev)
                    && !Character.isWhitespace(first)
                    && !isClosingOrAttachingPunctuation(first)
                    && !(isCjk(prev) && isCjk(first))) {
                prependSpace = true;
            }
        }
        boolean appendSpace = false;
        if (after != null && after.length() > 0) {
            char last = text.charAt(text.length() - 1);
            char next = after.charAt(0);
            if (!Character.isWhitespace(last)
                    && !isOpeningDelimiter(last)
                    && !Character.isWhitespace(next)
                    && !isClosingOrAttachingPunctuation(next)
                    && !(isCjk(last) && isCjk(next))) {
                appendSpace = true;
            }
        }
        if (!prependSpace && !appendSpace) {
            return text;
        }
        StringBuilder builder = new StringBuilder(
                text.length() + (prependSpace ? 1 : 0) + (appendSpace ? 1 : 0));
        if (prependSpace) {
            builder.append(' ');
        }
        builder.append(text);
        if (appendSpace) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static void rememberLiveFlag(Object flagInstance, String flagName) {
        if (flagInstance == null) {
            return;
        }
        synchronized (FLAG_CACHE_LOCK) {
            LIVE_TOGGLE_FLAGS.put(flagInstance, flagName);
        }
        if (!PREF_LISTENER_ATTACHED.get()) {
            attachDefaultPrefsListenerIfNeeded(resolveContext());
        }
    }

    private static String readFlagName(Object flagInstance) throws Exception {
        FlagHandles handles = resolveHandles(flagInstance.getClass());
        if (handles == null || handles.nameField == null) {
            return null;
        }
        Object value = handles.nameField.get(flagInstance);
        return value instanceof String text ? text : null;
    }

    private static FlagHandles resolveHandles(Class<?> flagClass) {
        FlagHandles existing = cachedHandles;
        if (existing != null && existing.flagClass == flagClass) {
            return existing;
        }
        FlagHandles created = FlagHandles.forClass(flagClass);
        cachedHandles = created;
        return created;
    }

    private static Object enableBoolean(Object originalResult) {
        return originalResult instanceof Boolean ? Boolean.TRUE : originalResult;
    }

    private static boolean isOpeningDelimiter(char ch) {
        return ch == '(' || ch == '[' || ch == '{' || ch == '<' || ch == '/'
                || ch == '\u201C' || ch == '\u2018' || ch == '\uFF08'
                || ch == '\u300C' || ch == '\u300E' || ch == '\u3010' || ch == '\u300A';
    }

    private static boolean isClosingOrAttachingPunctuation(char ch) {
        return ch == '.' || ch == ',' || ch == '!' || ch == '?' || ch == ';' || ch == ':'
                || ch == ')' || ch == ']' || ch == '}' || ch == '>' || ch == '\'' || ch == '"'
                || ch == '%' || ch == '\u201D' || ch == '\u2019' || ch == '\u2026'
                || ch == '\u3002' || ch == '\uFF0C' || ch == '\uFF01' || ch == '\uFF1F'
                || ch == '\uFF1B' || ch == '\uFF1A' || ch == '\u3001' || ch == '\uFF09'
                || ch == '\u300D' || ch == '\u300F' || ch == '\u3011' || ch == '\u300B';
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }

    private static Context resolveContext() {
        Context cached = applicationContext;
        if (cached != null) {
            return cached;
        }
        Context resolved = reflectedApplicationContext(
                "android.app.ActivityThread", "currentApplication");
        if (resolved == null) {
            resolved = reflectedApplicationContext(
                    "android.app.AppGlobals", "getInitialApplication");
        }
        if (resolved != null) {
            applicationContext = resolved;
        }
        return resolved;
    }

    private static Context reflectedApplicationContext(String className, String methodName) {
        try {
            Class<?> owner = Class.forName(className);
            Method method = owner.getMethod(methodName);
            Object value = method.invoke(null);
            if (!(value instanceof Application)) {
                return null;
            }
            Context context = (Context) value;
            Context app = context.getApplicationContext();
            return app != null ? app : context;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logWarningSafely(String message, Throwable throwable) {
        try {
            Log.w(TAG, message, throwable);
        } catch (Throwable ignored) {
            // Hooks must never throw into Gboard.
        }
    }

    private static final class FlagHandles {
        final Class<?> flagClass;
        final Field nameField;
        final Method recomputeMethod;
        final Method notifyMethod;

        private FlagHandles(
                Class<?> flagClass,
                Field nameField,
                Method recomputeMethod,
                Method notifyMethod) {
            this.flagClass = flagClass;
            this.nameField = nameField;
            this.recomputeMethod = recomputeMethod;
            this.notifyMethod = notifyMethod;
        }

        static FlagHandles forClass(Class<?> cls) {
            Field nameField = null;
            try {
                if (RamblerDynamicConfig.FLAG_NAME_FIELD != null
                        && !RamblerDynamicConfig.FLAG_NAME_FIELD.isEmpty()) {
                    Field f = cls.getDeclaredField(RamblerDynamicConfig.FLAG_NAME_FIELD);
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        nameField = f;
                    }
                }
            } catch (Throwable ignored) {
                // Fall back to scanning for the String field.
            }
            if (nameField == null) {
                for (Field candidate : cls.getDeclaredFields()) {
                    if (candidate.getType() == String.class) {
                        candidate.setAccessible(true);
                        nameField = candidate;
                        break;
                    }
                }
            }

            Method recompute = findNoArgMethod(cls, RamblerDynamicConfig.FLAG_RECOMPUTE_METHOD, boolean.class);
            Method notify = findNoArgMethod(cls, RamblerDynamicConfig.FLAG_NOTIFY_METHOD, void.class);
            return new FlagHandles(cls, nameField, recompute, notify);
        }

        private static Method findNoArgMethod(
                Class<?> cls, String name, Class<?> expectedReturn) {
            if (name == null || name.isEmpty()) {
                return null;
            }
            try {
                Method method = cls.getDeclaredMethod(name);
                if (method.getReturnType() != expectedReturn) {
                    return null;
                }
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
