package dev.abotmaker.ramblerenhancements;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Direct-Boot-safe standalone SharedPreferences store for Rambler behavior toggles
 * and seeded contact history.
 *
 * <p>Live boolean toggles are stored in Gboard's default SharedPreferences
 * ({@code <packageName>_preferences}) so native {@code SwitchPreferenceCompat} rows injected
 * directly into Gboard's "Rambler voice typing" screen and {@link RamblerEnhancementsActivity}
 * always read and write the exact same keys in real time.</p>
 */
public final class RamblerEnhancementsSettings {
    private static final String TAG = "RamblerEnhancements";
    public static final String PREF_FILE = "rambler_enhancements_prefs";

    public static final String PREF_KEY_USE_DICTIONARY_WORDS =
            "pref_rambler_use_dictionary_words";
    public static final String PREF_KEY_APPEND_ONLY = "pref_rambler_append_only";
    public static final String PREF_KEY_CURSOR_EDITING_ENABLED =
            "pref_rambler_cursor_editing_enabled";
    public static final String PREF_KEY_APP_CONTEXT_ENABLED =
            "pref_rambler_app_context_enabled";
    public static final String PREF_KEY_SEEDED_CONTACTS =
            "pref_rambler_contacts_seeded_names";

    public static final boolean DEFAULT_USE_DICTIONARY_WORDS = true;
    public static final boolean DEFAULT_APPEND_ONLY = false;
    public static final boolean DEFAULT_CURSOR_EDITING_ENABLED = true;
    public static final boolean DEFAULT_APP_CONTEXT_ENABLED = true;

    private RamblerEnhancementsSettings() {
    }

    /** Gboard's default SharedPreferences file shared with native SwitchPreferenceCompat rows. */
    public static SharedPreferences defaultPreferencesOrNull(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        Context base = app != null ? app : context;
        String defaultName = base.getPackageName() + "_preferences";
        try {
            return base.getSharedPreferences(defaultName, Context.MODE_PRIVATE);
        } catch (IllegalStateException directBootLocked) {
            try {
                return base.createDeviceProtectedStorageContext()
                        .getSharedPreferences(defaultName, Context.MODE_PRIVATE);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable throwable) {
            logWarningSafely("Failed to open default SharedPreferences", throwable);
            return null;
        }
    }

    public static SharedPreferences preferencesOrNull(Context context) {
        if (context == null) {
            return null;
        }
        Context app = context.getApplicationContext();
        Context base = app != null ? app : context;
        try {
            return base.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        } catch (IllegalStateException directBootLocked) {
            try {
                return base.createDeviceProtectedStorageContext()
                        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            } catch (Throwable ignored) {
                return null;
            }
        } catch (Throwable throwable) {
            logWarningSafely("Failed to open Rambler enhancements preferences", throwable);
            return null;
        }
    }

    public static boolean isLiveToggleKey(String key) {
        return PREF_KEY_USE_DICTIONARY_WORDS.equals(key)
                || PREF_KEY_APPEND_ONLY.equals(key)
                || PREF_KEY_CURSOR_EDITING_ENABLED.equals(key)
                || PREF_KEY_APP_CONTEXT_ENABLED.equals(key);
    }

    public static boolean readUseDictionaryWords(Context context) {
        return readLiveBoolean(
                context, PREF_KEY_USE_DICTIONARY_WORDS, DEFAULT_USE_DICTIONARY_WORDS);
    }

    public static boolean readAppendOnly(Context context) {
        return readLiveBoolean(context, PREF_KEY_APPEND_ONLY, DEFAULT_APPEND_ONLY);
    }

    public static boolean readCursorEditingEnabled(Context context) {
        return readLiveBoolean(
                context, PREF_KEY_CURSOR_EDITING_ENABLED, DEFAULT_CURSOR_EDITING_ENABLED);
    }

    public static boolean readAppContextEnabled(Context context) {
        return readLiveBoolean(
                context, PREF_KEY_APP_CONTEXT_ENABLED, DEFAULT_APP_CONTEXT_ENABLED);
    }

    public static boolean writeUseDictionaryWords(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeLiveBoolean(context, PREF_KEY_USE_DICTIONARY_WORDS, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static boolean writeAppendOnly(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeLiveBoolean(context, PREF_KEY_APPEND_ONLY, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static boolean writeCursorEditingEnabled(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeLiveBoolean(context, PREF_KEY_CURSOR_EDITING_ENABLED, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static boolean writeAppContextEnabled(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeLiveBoolean(context, PREF_KEY_APP_CONTEXT_ENABLED, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static Set<String> readSeededContacts(Context context) {
        try {
            SharedPreferences prefs = preferencesOrNull(context);
            if (prefs == null) {
                return Collections.emptySet();
            }
            Set<String> stored = prefs.getStringSet(PREF_KEY_SEEDED_CONTACTS, null);
            return stored == null
                    ? Collections.<String>emptySet()
                    : new HashSet<String>(stored);
        } catch (Throwable throwable) {
            logWarningSafely("Failed to read seeded contact names", throwable);
            return Collections.emptySet();
        }
    }

    public static boolean writeSeededContacts(Context context, Set<String> seeded) {
        try {
            SharedPreferences prefs = preferencesOrNull(context);
            if (prefs == null) {
                return false;
            }
            return prefs.edit()
                    .putStringSet(
                            PREF_KEY_SEEDED_CONTACTS,
                            seeded == null
                                    ? Collections.<String>emptySet()
                                    : new HashSet<String>(seeded))
                    .commit();
        } catch (Throwable throwable) {
            logWarningSafely("Failed to persist seeded contact names", throwable);
            return false;
        }
    }

    public static boolean clearSeededContacts(Context context) {
        try {
            SharedPreferences prefs = preferencesOrNull(context);
            return prefs != null && prefs.edit().remove(PREF_KEY_SEEDED_CONTACTS).commit();
        } catch (Throwable throwable) {
            logWarningSafely("Failed to clear seeded contact history", throwable);
            return false;
        }
    }

    private static boolean readLiveBoolean(Context context, String key, boolean fallback) {
        SharedPreferences defaultPrefs = defaultPreferencesOrNull(context);
        Boolean fromDefault = readBooleanOrNull(defaultPrefs, key);
        if (fromDefault != null) {
            return fromDefault.booleanValue();
        }
        // Fallback for values written under v1.0.0's dedicated prefs file.
        Boolean fromLegacy = readBooleanOrNull(preferencesOrNull(context), key);
        return fromLegacy != null ? fromLegacy.booleanValue() : fallback;
    }

    private static boolean writeLiveBoolean(Context context, String key, boolean enabled) {
        try {
            SharedPreferences defaultPrefs = defaultPreferencesOrNull(context);
            boolean wroteDefault = defaultPrefs != null
                    && defaultPrefs.edit().putBoolean(key, enabled).commit();
            SharedPreferences dedicated = preferencesOrNull(context);
            if (dedicated != null) {
                dedicated.edit().putBoolean(key, enabled).apply();
            }
            return wroteDefault;
        } catch (Throwable unavailable) {
            logWarningSafely("Failed to persist Rambler toggle " + key, unavailable);
            return false;
        }
    }

    private static Boolean readBooleanOrNull(SharedPreferences preferences, String key) {
        if (preferences == null) {
            return null;
        }
        try {
            Object raw = preferences.getAll().get(key);
            if (raw instanceof Boolean boolValue) {
                return boolValue;
            }
            if (raw instanceof String textValue) {
                if ("true".equalsIgnoreCase(textValue.trim())) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(textValue.trim())) {
                    return Boolean.FALSE;
                }
            }
            return null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static void logWarningSafely(String message, Throwable throwable) {
        try {
            Log.w(TAG, message, throwable);
        } catch (Throwable ignored) {
            // Never propagate settings logging errors into Gboard.
        }
    }
}
