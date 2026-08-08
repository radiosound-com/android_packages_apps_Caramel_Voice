/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Keeps one context-biased Zipformer model warm and leases streams safely. */
final class SherpaModelRepository {
    private static final String TAG = "CaramelVoice";
    private static final int MAX_MODEL_HOTWORDS = 1024;
    private static final long CONTEXT_DEBOUNCE_MS = 2500;
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService LOADER =
            Executors.newSingleThreadScheduledExecutor();

    private static Context applicationContext;
    private static RecognitionBackendProfile profile;
    private static OnlineRecognizer recognizer;
    private static String loadedHotwords;
    private static ScheduledFuture<?> scheduledReload;
    private static boolean preloadStarted;
    private static boolean loading;
    private static boolean reloadPending;
    private static int activeLeases;

    private SherpaModelRepository() {}

    static void preload(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (preloadStarted) return;
            profile = RecognitionBackendProfile.load();
            if (profile.engine != RecognitionBackendProfile.Engine.ZIPFORMER) return;
            preloadStarted = true;
            applicationContext = app;
        }

        RecognitionContextRepository.preload(app);
        RecognitionContextRepository.addChangeListener(SherpaModelRepository::contextChanged);
        scheduleReload(CONTEXT_DEBOUNCE_MS);
    }

    static Lease acquire(Context context, long timeout, TimeUnit unit) throws InterruptedException {
        preload(context);
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (LOCK) {
            while (recognizer == null) {
                if (profile == null || profile.engine != RecognitionBackendProfile.Engine.ZIPFORMER) {
                    return null;
                }
                if (remainingNanos <= 0) return null;
                TimeUnit.NANOSECONDS.timedWait(LOCK, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
            activeLeases++;
            return new Lease(recognizer);
        }
    }

    private static void contextChanged() {
        scheduleReload(CONTEXT_DEBOUNCE_MS);
    }

    private static void scheduleReload(long delayMs) {
        synchronized (LOCK) {
            if (!preloadStarted) return;
            if (loading || activeLeases > 0) {
                reloadPending = true;
                return;
            }
            if (scheduledReload != null) scheduledReload.cancel(false);
            scheduledReload = LOADER.schedule(
                    SherpaModelRepository::reload, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void reload() {
        Context context;
        RecognitionBackendProfile selectedProfile;
        String hotwords;
        OnlineRecognizer oldRecognizer;
        synchronized (LOCK) {
            scheduledReload = null;
            if (loading || activeLeases > 0) {
                reloadPending = true;
                return;
            }
            context = applicationContext;
            selectedProfile = profile;
            hotwords = RecognitionContextRepository
                    .snapshot(context, MAX_MODEL_HOTWORDS)
                    .asZipformerHotwords();
            if (recognizer != null && hotwords.equals(loadedHotwords)) return;
            loading = true;
            reloadPending = false;
            oldRecognizer = recognizer;
            recognizer = null;
            loadedHotwords = null;
        }

        if (oldRecognizer != null) oldRecognizer.release();
        OnlineRecognizer loaded = null;
        try {
            long startedAt = SystemClock.elapsedRealtime();
            loaded = build(context, selectedProfile, hotwords);
            Log.i(TAG, "Zipformer model ready in "
                    + (SystemClock.elapsedRealtime() - startedAt) + " ms with "
                    + countLines(hotwords) + " context phrases");
        } catch (IOException | RuntimeException | UnsatisfiedLinkError exception) {
            Log.e(TAG, "Unable to load Zipformer model", exception);
            if (loaded != null) loaded.release();
            loaded = null;
        }

        boolean scheduleAgain;
        synchronized (LOCK) {
            recognizer = loaded;
            loadedHotwords = loaded == null ? null : hotwords;
            loading = false;
            scheduleAgain = reloadPending;
            LOCK.notifyAll();
        }
        if (scheduleAgain) scheduleReload(CONTEXT_DEBOUNCE_MS);
    }

    private static OnlineRecognizer build(
            Context context, RecognitionBackendProfile selectedProfile, String hotwords)
            throws IOException {
        SherpaModelPaths paths = new SherpaModelPaths();
        paths.validate();
        File hotwordsFile = new File(context.getNoBackupFilesDir(), "zipformer-hotwords.txt");
        try (FileOutputStream output = new FileOutputStream(hotwordsFile, false)) {
            output.write(hotwords.getBytes(StandardCharsets.UTF_8));
            if (!hotwords.isEmpty()) output.write('\n');
        }

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(paths.encoder);
        transducer.setDecoder(paths.decoder);
        transducer.setJoiner(paths.joiner);

        OnlineModelConfig model = new OnlineModelConfig();
        model.setTransducer(transducer);
        model.setTokens(paths.tokens);
        model.setNumThreads(selectedProfile.threads);
        model.setProvider("cpu");
        model.setModelType("zipformer");
        model.setModelingUnit("bpe");
        model.setBpeVocab(paths.bpeVocab);

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setModelConfig(model);
        config.setEnableEndpoint(false);
        config.setDecodingMethod(selectedProfile.decodingMethod);
        config.setMaxActivePaths(selectedProfile.maxActivePaths);
        config.setHotwordsFile(hotwordsFile.getAbsolutePath());
        config.setHotwordsScore(selectedProfile.hotwordsScore);
        return new OnlineRecognizer(null, config);
    }

    private static int countLines(String value) {
        if (value.isEmpty()) return 0;
        int count = 1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') count++;
        }
        return count;
    }

    static final class Lease implements AutoCloseable {
        private final OnlineRecognizer leasedRecognizer;
        private boolean closed;

        private Lease(OnlineRecognizer recognizer) {
            leasedRecognizer = recognizer;
        }

        OnlineRecognizer recognizer() {
            return leasedRecognizer;
        }

        @Override
        public void close() {
            boolean shouldReload;
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                activeLeases--;
                shouldReload = activeLeases == 0 && reloadPending;
                LOCK.notifyAll();
            }
            if (shouldReload) scheduleReload(0);
        }
    }
}
