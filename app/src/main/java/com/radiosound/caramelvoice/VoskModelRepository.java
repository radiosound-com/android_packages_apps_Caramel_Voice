/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Keeps the product-selected Vosk model warm for the lifetime of the app process. */
final class VoskModelRepository {
    private static final String TAG = "CaramelVoice";
    private static final float SAMPLE_RATE = 16000.0f;
    private static final int PREWARM_CHUNK_SAMPLES = 1600;
    private static final int PREWARM_CHUNKS = 5;

    private static final Object LOCK = new Object();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final RecognitionReadinessGate READINESS = new RecognitionReadinessGate();

    private static volatile boolean loadStarted;
    private static volatile Model model;
    private static volatile CountDownLatch loadComplete = new CountDownLatch(1);

    private VoskModelRepository() {}

    static void preload(Context context) {
        synchronized (LOCK) {
            if (loadStarted) return;
            loadStarted = true;
            loadComplete = new CountDownLatch(1);
            READINESS.reset();
        }

        Context applicationContext = context.getApplicationContext();
        LOADER.execute(() -> load(applicationContext));
    }

    static void whenReady(Context context, RecognitionReadinessGate.Callback callback) {
        synchronized (LOCK) {
            if (loadStarted && model == null && READINESS.isComplete()
                    && !READINESS.isAvailable()) {
                loadStarted = false;
            }
        }
        preload(context);
        READINESS.await(callback);
    }

    static Model await(Context context, long timeout, TimeUnit unit)
            throws InterruptedException {
        preload(context);
        CountDownLatch currentLoad = loadComplete;
        if (!currentLoad.await(timeout, unit)) return null;
        return model;
    }

    private static void load(Context context) {
        Model loadedModel = null;
        boolean available = false;
        try {
            VoskModelProfile profile = VoskModelProfile.load();
            String modelPath = VoskModelStore.sync(context, profile);
            long startedAt = SystemClock.elapsedRealtime();
            loadedModel = new Model(modelPath);
            prewarm(loadedModel);
            model = loadedModel;
            available = true;
            Log.i(TAG, "Vosk model ready and prewarmed in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms: "
                    + profile.modelDirectory + " at " + modelPath);
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Unable to load Vosk model", exception);
            if (loadedModel != null) loadedModel.close();
            model = null;
        } finally {
            READINESS.complete(available);
            loadComplete.countDown();
        }
    }

    private static void prewarm(Model loadedModel) throws IOException {
        Recognizer recognizer = new Recognizer(loadedModel, SAMPLE_RATE);
        try {
            short[] silence = new short[PREWARM_CHUNK_SAMPLES];
            for (int index = 0; index < PREWARM_CHUNKS; index++) {
                recognizer.acceptWaveForm(silence, silence.length);
            }
            recognizer.getPartialResult();
        } finally {
            recognizer.close();
        }
    }
}
