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
    private RamblerDictationHaptics() {
    }

    static void playListeningCue(boolean started) {
        playListeningCue(RamblerFlagRuntime.resolveContext(), started);
    }

    static void playListeningCue(Context context, boolean started) {
        try {
            if (context == null
                    || !RamblerEnhancementsSettings.readDictationHapticsEnabled(context)
                    || Settings.System.getInt(context.getContentResolver(),
                            Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0
                    || context.checkSelfPermission(Manifest.permission.VIBRATE)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            Vibrator vibrator = resolveVibrator(context);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }

            VibrationEffect effect;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                effect = VibrationEffect.createPredefined(
                        started ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK);
            } else {
                effect = VibrationEffect.createOneShot(
                        started ? 20 : 10, VibrationEffect.DEFAULT_AMPLITUDE);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect);
            }
        } catch (Throwable ignored) {
            // Haptic feedback is optional and must never interrupt dictation.
        }
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
