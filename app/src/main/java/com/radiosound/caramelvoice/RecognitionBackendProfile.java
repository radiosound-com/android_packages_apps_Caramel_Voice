/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Product-selected recognition backend and tuning values. */
final class RecognitionBackendProfile {
    static final String CONFIG_PATH = "/product/etc/caramel_voice/recognition.properties";

    enum Engine {
        VOSK,
        ZIPFORMER
    }

    final Engine engine;
    final int threads;
    final String decodingMethod;
    final int maxActivePaths;
    final float hotwordsScore;

    private RecognitionBackendProfile(
            Engine engine,
            int threads,
            String decodingMethod,
            int maxActivePaths,
            float hotwordsScore) {
        this.engine = engine;
        this.threads = threads;
        this.decodingMethod = decodingMethod;
        this.maxActivePaths = maxActivePaths;
        this.hotwordsScore = hotwordsScore;
    }

    static RecognitionBackendProfile load() {
        return load(null);
    }

    /**
     * Loads the immutable product profile. A debuggable build may opt into an
     * external profile for emulator/model smoke tests; release builds never
     * consult app-writable storage for backend selection.
     */
    static RecognitionBackendProfile load(Context context) {
        Properties properties = new Properties();
        File config = configFile(context);
        if (config.isFile()) {
            try (FileInputStream input = new FileInputStream(config)) {
                properties.load(input);
            } catch (IOException ignored) {
                // The compact Vosk backend is the safe fallback for a malformed product file.
            }
        }
        return fromProperties(properties);
    }

    private static File configFile(Context context) {
        if (isDebuggable(context)) {
            File external = context.getExternalFilesDir(null);
            if (external != null) {
                File override = new File(external, "recognition.properties");
                if (override.isFile()) return override;
            }
        }
        return new File(CONFIG_PATH);
    }

    private static boolean isDebuggable(Context context) {
        return context != null
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    static RecognitionBackendProfile fromProperties(Properties properties) {
        String engine = properties.getProperty("engine", "vosk").trim();
        if (!"zipformer".equals(engine)) {
            return new RecognitionBackendProfile(Engine.VOSK, 1, "greedy_search", 1, 0.0f);
        }
        return new RecognitionBackendProfile(
                Engine.ZIPFORMER,
                boundedInteger(properties, "threads", 4, 1, 8),
                "modified_beam_search".equals(
                        properties.getProperty("decoding_method", "modified_beam_search").trim())
                        ? "modified_beam_search" : "greedy_search",
                boundedInteger(properties, "max_active_paths", 4, 1, 16),
                boundedFloat(properties, "hotwords_score", 4.0f, 0.0f, 10.0f));
    }

    private static int boundedInteger(
            Properties properties, String key, int fallback, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, "").trim());
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static float boundedFloat(
            Properties properties, String key, float fallback, float minimum, float maximum) {
        try {
            float value = Float.parseFloat(properties.getProperty(key, "").trim());
            if (!Float.isFinite(value)) return fallback;
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
