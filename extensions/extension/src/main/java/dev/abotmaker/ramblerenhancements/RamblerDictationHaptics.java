package dev.abotmaker.ramblerenhancements;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

final class RamblerDictationHaptics {
    private static final String SETTING_HAPTIC_FEEDBACK_INTENSITY = "haptic_feedback_intensity";

    private RamblerDictationHaptics() {
    }

    static void playListeningCue(boolean started) {
        playListeningCue(RamblerFlagRuntime.resolveContext(), started);
    }

    static void playListeningCue(Context context, boolean started) {
        try {
            if (context == null
                    || !RamblerEnhancementsSettings.readDictationHapticsEnabled(context)
                    || context.checkSelfPermission(Manifest.permission.VIBRATE)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Vibrator vibrator = resolveVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            VibrationEffect effect = buildEffect(vibrator, started);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Respect the user's touch feedback intensity when system Touch feedback is on;
                // when Touch feedback is off, fall back to USAGE_ALARM so this explicit opt-in
                // dictation cue is not silently dropped by VibratorManagerService.
                int usage = isSystemTouchFeedbackEnabled(context)
                        ? VibrationAttributes.USAGE_TOUCH
                        : VibrationAttributes.USAGE_ALARM;
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage));
            } else {
                vibrator.vibrate(effect);
            }
        } catch (Throwable ignored) {
            // Haptic feedback is optional and must never interrupt dictation.
        }
    }

    private static VibrationEffect buildEffect(Vibrator vibrator, boolean started) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (started
                    && vibrator.areAllPrimitivesSupported(
                            VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                return VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                        .compose();
            }
            if (!started) {
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_TICK)) {
                    return VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                            .compose();
                }
                if (vibrator.areAllPrimitivesSupported(
                        VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                    return VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f)
                            .compose();
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return VibrationEffect.createPredefined(
                    started ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK);
        }
        return VibrationEffect.createOneShot(
                started ? 22 : 12, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    @SuppressWarnings("deprecation")
    private static boolean isSystemTouchFeedbackEnabled(Context context) {
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && Settings.System.getInt(context.getContentResolver(),
                            SETTING_HAPTIC_FEEDBACK_INTENSITY, 1) == 0) {
                return false;
            }
        } catch (Throwable ignored) {
            // Treat unreadable settings as enabled.
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static Vibrator resolveVibrator(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            if (manager != null) {
                Vibrator defaultVibrator = manager.getDefaultVibrator();
                if (defaultVibrator != null) {
                    return defaultVibrator;
                }
            }
        }
        return context.getSystemService(Vibrator.class);
    }
}
