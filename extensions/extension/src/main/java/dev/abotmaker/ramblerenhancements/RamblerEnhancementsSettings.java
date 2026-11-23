package dev.abotmaker.ramblerenhancements;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Direct-Boot-safe standalone SharedPreferences store for Rambler behavior toggles
 * and seeded contact history.
 *
 * <p>Live boolean toggles are synced directly with Gboard's live Preferences store
 * ({@code qvt} Proto Preference v1, backing native {@code SwitchPreferenceCompat} controls
 * injected into Gboard's "Rambler voice typing" screen) with a fallback to Android's default
 * {@code <packageName>_preferences} when {@code qvt} is unavailable (e.g. unit tests).</p>
 */
public final class RamblerEnhancementsSettings {
    private static final String TAG = "RamblerEnhancements";
    public static final String PREF_FILE = "rambler_enhancements_prefs";

    public static final String PREF_KEY_USE_DICTIONARY_WORDS =
            "pref_rambler_use_dictionary_words";
    public static final String PREF_KEY_APPEND_ONLY = "pref_rambler_append_only";
    public static final String PREF_KEY_CURSOR_EDITING_ENABLED =
            "pref_rambler_cursor_editing_enabled";
    public static final String PREF_KEY_RESELECT_AFTER_EDIT =
            "pref_rambler_reselect_after_edit";
    public static final String PREF_KEY_APP_CONTEXT_ENABLED =
            "pref_rambler_app_context_enabled";
    public static final String PREF_KEY_SEEDED_CONTACTS =
            "pref_rambler_contacts_seeded_names";

    private static final String[] LIVE_TOGGLE_KEYS = {
            PREF_KEY_USE_DICTIONARY_WORDS,
            PREF_KEY_APPEND_ONLY,
            PREF_KEY_CURSOR_EDITING_ENABLED,
            PREF_KEY_RESELECT_AFTER_EDIT,
            PREF_KEY_APP_CONTEXT_ENABLED
    };

    public static final boolean DEFAULT_USE_DICTIONARY_WORDS = true;
    public static final boolean DEFAULT_APPEND_ONLY = false;
    public static final boolean DEFAULT_CURSOR_EDITING_ENABLED = true;
    public static final boolean DEFAULT_RESELECT_AFTER_EDIT = false;
    public static final boolean DEFAULT_APP_CONTEXT_ENABLED = true;

    private static final ThreadLocal<Boolean> IN_GBOARD_PREFS_LOOKUP = new ThreadLocal<Boolean>();
    private static volatile GboardPrefsBridge cachedGboardBridge;
    private static volatile Object retainedGboardListener;
    private static volatile SharedPreferences.OnSharedPreferenceChangeListener retainedBackingListener;

    private RamblerEnhancementsSettings() {
    }

    static void clearGboardBridgeForTesting() {
        cachedGboardBridge = null;
        retainedGboardListener = null;
        retainedBackingListener = null;
    }

    /**
     * Returns the live {@link SharedPreferences} backing Gboard's internal {@code Preferences}
     * store ({@code qvt.e.get()} — Proto Preference v1), where native
     * {@code SwitchPreferenceCompat} controls in {@code setting_jetson_voice.xml} persist values.
     */
    public static SharedPreferences gboardBackingPreferencesOrNull(Context context) {
        if (context == null || Boolean.TRUE.equals(IN_GBOARD_PREFS_LOOKUP.get())) {
            return null;
        }
        IN_GBOARD_PREFS_LOOKUP.set(Boolean.TRUE);
        try {
            GboardPrefsBridge bridge = resolveGboardBridge(context);
            if (bridge == null) {
                return null;
            }
            Object prefsInstance = bridge.getInstance(context);
            if (prefsInstance == null) {
                return null;
            }
            return bridge.resolveBackingSharedPreferences(prefsInstance);
        } catch (Throwable ignored) {
            return null;
        } finally {
            IN_GBOARD_PREFS_LOOKUP.remove();
        }
    }

    /**
     * Registers a live listener on Gboard's internal {@code Preferences} store ({@code qvt}) so
     * flipping any native {@code SwitchPreferenceCompat} on Gboard's "Rambler voice typing" screen
     * immediately refreshes runtime flags without restarting Gboard.
     *
     * @return {@code true} if attached directly to Gboard's live {@code Preferences} store.
     */
    public static boolean registerGboardToggleListener(Context context, Runnable onToggleChanged) {
        if (context == null || onToggleChanged == null
                || Boolean.TRUE.equals(IN_GBOARD_PREFS_LOOKUP.get())) {
            return false;
        }
        IN_GBOARD_PREFS_LOOKUP.set(Boolean.TRUE);
        try {
            GboardPrefsBridge bridge = resolveGboardBridge(context);
            if (bridge == null) {
                return false;
            }
            Object prefsInstance = bridge.getInstance(context);
            if (prefsInstance == null) {
                return false;
            }
            if (bridge.registerKeyListenerMethod != null && bridge.listenerInterface != null) {
                Object proxy = Proxy.newProxyInstance(
                        bridge.listenerInterface.getClassLoader(),
                        new Class<?>[]{bridge.listenerInterface},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object p, Method method, Object[] args) {
                                String name = method.getName();
                                if ("equals".equals(name)) {
                                    return args != null && args.length == 1 && p == args[0];
                                }
                                if ("hashCode".equals(name)) {
                                    return System.identityHashCode(p);
                                }
                                if ("toString".equals(name)) {
                                    return "RamblerGboardToggleListener";
                                }
                                onToggleChanged.run();
                                return null;
                            }
                        });
                retainedGboardListener = proxy;
                for (String key : LIVE_TOGGLE_KEYS) {
                    bridge.registerKeyListenerMethod.invoke(prefsInstance, proxy, key);
                }
                return true;
            }
            SharedPreferences backing = bridge.resolveBackingSharedPreferences(prefsInstance);
            if (backing != null) {
                SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                    if (isLiveToggleKey(key)) {
                        onToggleChanged.run();
                    }
                };
                retainedBackingListener = listener;
                backing.registerOnSharedPreferenceChangeListener(listener);
                return true;
            }
            return false;
        } catch (Throwable throwable) {
            logWarningSafely("Failed to attach Gboard Preferences live toggle listener", throwable);
            return false;
        } finally {
            IN_GBOARD_PREFS_LOOKUP.remove();
        }
    }

    /** Fallback Android default SharedPreferences ({@code <packageName>_preferences}). */
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
                || PREF_KEY_RESELECT_AFTER_EDIT.equals(key)
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

    public static boolean readReselectAfterEditEnabled(Context context) {
        return readLiveBoolean(
                context, PREF_KEY_RESELECT_AFTER_EDIT, DEFAULT_RESELECT_AFTER_EDIT);
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

    public static boolean writeReselectAfterEditEnabled(Context context, boolean enabled) {
        RamblerFlagRuntime.seedApplicationContext(context);
        boolean ok = writeLiveBoolean(context, PREF_KEY_RESELECT_AFTER_EDIT, enabled);
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
        // 1. Primary: Gboard's live Proto Preference v1 store (written by native switches in
        //    setting_jetson_voice.xml and RamblerEnhancementsActivity).
        SharedPreferences gboardPrefs = gboardBackingPreferencesOrNull(context);
        Boolean fromGboard = readBooleanOrNull(gboardPrefs, key);
        if (fromGboard != null) {
            return fromGboard.booleanValue();
        }
        // 2. Fallback: standard Android default SharedPreferences (<pkg>_preferences).
        SharedPreferences defaultPrefs = defaultPreferencesOrNull(context);
        Boolean fromDefault = readBooleanOrNull(defaultPrefs, key);
        if (fromDefault != null) {
            return fromDefault.booleanValue();
        }
        // 3. Fallback: values written under v1.0.0's dedicated prefs file.
        Boolean fromLegacy = readBooleanOrNull(preferencesOrNull(context), key);
        return fromLegacy != null ? fromLegacy.booleanValue() : fallback;
    }

    private static boolean writeLiveBoolean(Context context, String key, boolean enabled) {
        try {
            boolean wroteGboard = writeGboardBoolean(context, key, enabled);
            SharedPreferences defaultPrefs = defaultPreferencesOrNull(context);
            boolean wroteDefault = defaultPrefs != null
                    && defaultPrefs.edit().putBoolean(key, enabled).commit();
            SharedPreferences dedicated = preferencesOrNull(context);
            if (dedicated != null) {
                dedicated.edit().putBoolean(key, enabled).apply();
            }
            return wroteGboard || wroteDefault;
        } catch (Throwable unavailable) {
            logWarningSafely("Failed to persist Rambler toggle " + key, unavailable);
            return false;
        }
    }

    private static boolean writeGboardBoolean(Context context, String key, boolean enabled) {
        if (context == null || Boolean.TRUE.equals(IN_GBOARD_PREFS_LOOKUP.get())) {
            return false;
        }
        IN_GBOARD_PREFS_LOOKUP.set(Boolean.TRUE);
        try {
            GboardPrefsBridge bridge = resolveGboardBridge(context);
            if (bridge == null) {
                return false;
            }
            Object prefsInstance = bridge.getInstance(context);
            if (prefsInstance == null) {
                return false;
            }
            if (bridge.writeBoolMethod != null) {
                bridge.writeBoolMethod.invoke(prefsInstance, key, Boolean.valueOf(enabled));
                return true;
            }
            SharedPreferences backing = bridge.resolveBackingSharedPreferences(prefsInstance);
            return backing != null && backing.edit().putBoolean(key, enabled).commit();
        } catch (Throwable ignored) {
            return false;
        } finally {
            IN_GBOARD_PREFS_LOOKUP.remove();
        }
    }

    private static Boolean readBooleanOrNull(SharedPreferences preferences, String key) {
        if (preferences == null) {
            return null;
        }
        try {
            if (preferences.contains(key)) {
                try {
                    return Boolean.valueOf(preferences.getBoolean(key, false));
                } catch (ClassCastException ignored) {
                    // Fall through to getAll() string parsing.
                }
            }
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

    private static GboardPrefsBridge resolveGboardBridge(Context context) {
        String className = RamblerDynamicConfig.PREFS_CLASS;
        if (className == null || className.isEmpty()) {
            return null;
        }
        GboardPrefsBridge existing = cachedGboardBridge;
        if (existing != null && className.equals(existing.className)) {
            return existing;
        }
        ClassLoader loader = context.getClassLoader();
        if (loader == null) {
            loader = RamblerEnhancementsSettings.class.getClassLoader();
        }
        try {
            GboardPrefsBridge created = GboardPrefsBridge.load(className, loader);
            cachedGboardBridge = created;
            return created;
        } catch (Throwable ignored) {
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

    private static final class GboardPrefsBridge {
        final String className;
        final Class<?> prefsClass;
        final Method getInstanceMethod;
        final Field singletonField;
        final Method writeBoolMethod;
        final Method registerKeyListenerMethod;
        final Class<?> listenerInterface;
        volatile Field providerField;
        volatile Method providerGetMethod;

        private GboardPrefsBridge(
                String className,
                Class<?> prefsClass,
                Method getInstanceMethod,
                Field singletonField,
                Method writeBoolMethod,
                Method registerKeyListenerMethod,
                Class<?> listenerInterface) {
            this.className = className;
            this.prefsClass = prefsClass;
            this.getInstanceMethod = getInstanceMethod;
            this.singletonField = singletonField;
            this.writeBoolMethod = writeBoolMethod;
            this.registerKeyListenerMethod = registerKeyListenerMethod;
            this.listenerInterface = listenerInterface;
        }

        static GboardPrefsBridge load(String className, ClassLoader loader) throws Exception {
            Class<?> prefsClass = Class.forName(className, false, loader);
            Method getInstance = prefsClass.getMethod(
                    RamblerDynamicConfig.PREFS_GET_INSTANCE_METHOD, Context.class);

            Field singleton = null;
            for (Field field : prefsClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == prefsClass) {
                    field.setAccessible(true);
                    singleton = field;
                    break;
                }
            }

            Method writeBool = null;
            Method registerKeyListener = null;
            Class<?> listenerIface = null;
            for (Method method : prefsClass.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != void.class) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && params[0] == String.class
                        && params[1] == boolean.class
                        && writeBool == null) {
                    writeBool = method;
                } else if (params.length == 2
                        && params[0].isInterface()
                        && params[1] == String.class
                        && isGboardPrefListenerInterface(params[0], prefsClass)
                        && registerKeyListener == null) {
                    registerKeyListener = method;
                    listenerIface = params[0];
                }
            }

            return new GboardPrefsBridge(
                    className,
                    prefsClass,
                    getInstance,
                    singleton,
                    writeBool,
                    registerKeyListener,
                    listenerIface);
        }

        private static boolean isGboardPrefListenerInterface(Class<?> candidate, Class<?> prefsClass) {
            Method[] methods = candidate.getMethods();
            int abstractCount = 0;
            boolean matchesSignature = false;
            for (Method m : methods) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    abstractCount++;
                    Class<?>[] params = m.getParameterTypes();
                    if (m.getReturnType() == void.class
                            && params.length == 2
                            && params[0] == prefsClass
                            && params[1] == String.class) {
                        matchesSignature = true;
                    }
                }
            }
            return abstractCount == 1 && matchesSignature;
        }

        Object getInstance(Context context) throws Exception {
            if (singletonField != null) {
                Object existing = singletonField.get(null);
                if (existing != null) {
                    return existing;
                }
            }
            return getInstanceMethod.invoke(null, context);
        }

        SharedPreferences resolveBackingSharedPreferences(Object prefsInstance) {
            Field cachedField = providerField;
            Method cachedGet = providerGetMethod;
            if (cachedField != null && cachedGet != null) {
                try {
                    Object provider = cachedField.get(prefsInstance);
                    if (provider != null) {
                        Object value = cachedGet.invoke(provider);
                        if (value instanceof SharedPreferences sp) {
                            return sp;
                        }
                    }
                } catch (Throwable ignored) {
                    // Fall through to re-scan.
                }
            }
            for (Field field : prefsClass.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object provider = field.get(prefsInstance);
                    if (provider == null) {
                        continue;
                    }
                    if (provider instanceof SharedPreferences sp) {
                        return sp;
                    }
                    Method getMethod = provider.getClass().getMethod("get");
                    getMethod.setAccessible(true);
                    Object value = getMethod.invoke(provider);
                    if (value instanceof SharedPreferences sp) {
                        providerField = field;
                        providerGetMethod = getMethod;
                        return sp;
                    }
                } catch (Throwable ignored) {
                    // Not the SharedPreferences supplier field.
                }
            }
            return null;
        }
    }
}
