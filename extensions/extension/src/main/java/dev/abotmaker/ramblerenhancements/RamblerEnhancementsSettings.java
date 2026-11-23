package dev.abotmaker.ramblerenhancements;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Direct-Boot-safe standalone SharedPreferences store for Rambler behavior toggles
 * (append-only dictation, cursor-aware editing, app context) and seeded contact history.
 */
public final class RamblerEnhancementsSettings {
    private static final String TAG = "RamblerEnhancements";
    public static final String PREF_FILE = "rambler_enhancements_prefs";

    public static final String PREF_KEY_APPEND_ONLY = "pref_rambler_append_only";
    public static final String PREF_KEY_CURSOR_EDITING_ENABLED =
            "pref_rambler_cursor_editing_enabled";
    public static final String PREF_KEY_APP_CONTEXT_ENABLED =
            "pref_rambler_app_context_enabled";
    public static final String PREF_KEY_SEEDED_CONTACTS =
            "pref_rambler_contacts_seeded_names";

    public static final boolean DEFAULT_APPEND_ONLY = false;
    public static final boolean DEFAULT_CURSOR_EDITING_ENABLED = true;
    public static final boolean DEFAULT_APP_CONTEXT_ENABLED = true;

    private RamblerEnhancementsSettings() {
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

    public static boolean readAppendOnly(Context context) {
        return readBoolean(preferencesOrNull(context), PREF_KEY_APPEND_ONLY, DEFAULT_APPEND_ONLY);
    }

    public static boolean readCursorEditingEnabled(Context context) {
        return readBoolean(
                preferencesOrNull(context),
                PREF_KEY_CURSOR_EDITING_ENABLED,
                DEFAULT_CURSOR_EDITING_ENABLED);
    }

    public static boolean readAppContextEnabled(Context context) {
        return readBoolean(
                preferencesOrNull(context),
                PREF_KEY_APP_CONTEXT_ENABLED,
                DEFAULT_APP_CONTEXT_ENABLED);
    }

    public static boolean writeAppendOnly(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeBoolean(context, PREF_KEY_APPEND_ONLY, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static boolean writeCursorEditingEnabled(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeBoolean(context, PREF_KEY_CURSOR_EDITING_ENABLED, enabled);
        RamblerFlagRuntime.refreshLiveFlags();
        return ok;
    }

    public static boolean writeAppContextEnabled(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeBoolean(context, PREF_KEY_APP_CONTEXT_ENABLED, enabled);
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

    private static boolean readBoolean(
            SharedPreferences preferences, String key, boolean fallback) {
        if (preferences == null) {
            return fallback;
        }
        try {
            Object raw = preferences.getAll().get(key);
            if (raw instanceof Boolean boolValue) {
                return boolValue.booleanValue();
            }
            if (raw instanceof String textValue) {
                if ("true".equalsIgnoreCase(textValue.trim())) {
                    return true;
                }
                if ("false".equalsIgnoreCase(textValue.trim())) {
                    return false;
                }
            }
            return fallback;
        } catch (Throwable unreadable) {
            return fallback;
        }
    }

    private static boolean writeBoolean(Context context, String key, boolean enabled) {
        try {
            SharedPreferences preferences = preferencesOrNull(context);
            return preferences != null && preferences.edit().putBoolean(key, enabled).commit();
        } catch (Throwable unavailable) {
            logWarningSafely("Failed to persist Rambler toggle " + key, unavailable);
            return false;
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
