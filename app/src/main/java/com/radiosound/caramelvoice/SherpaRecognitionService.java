/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Android RecognitionService backed by a context-biased streaming INT8 Zipformer. */
public final class SherpaRecognitionService extends RecognitionService {
    private static final String TAG = "CaramelVoice";
    private static final long CAPTURE_WATCHDOG_MS = 25000;
    private static final long PROCESSING_TIMEOUT_MS = 10000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile BufferedSpeechService speechService;
    private volatile CloseOnce<StreamingSpeechDecoder> activeDecoder;
    private volatile Runnable recognitionTimeout;

    @Override
    public void onCreate() {
        super.onCreate();
        SherpaModelRepository.preload(this);
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        executor.execute(() -> {
            CloseOnce<StreamingSpeechDecoder> decoderOwner = null;
            try {
                SherpaModelRepository.Lease lease =
                        SherpaModelRepository.acquire(this, 30, TimeUnit.SECONDS);
                if (lease == null) {
                    reportError(listener, SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                SherpaStreamingSpeechDecoder decoder;
                try {
                    decoder = new SherpaStreamingSpeechDecoder(lease);
                } catch (RuntimeException | UnsatisfiedLinkError exception) {
                    lease.close();
                    throw exception;
                }
                decoderOwner = new CloseOnce<>(decoder);
                activeDecoder = decoderOwner;
                listener.readyForSpeech(new Bundle());

                BufferedSpeechService service = new BufferedSpeechService(decoder, 16000.0f);
                speechService = service;
                AtomicReference<ArrayList<String>> latestAlternatives =
                        new AtomicReference<>(new ArrayList<>());
                CloseOnce<StreamingSpeechDecoder> sessionDecoder = decoderOwner;
                scheduleRecognitionTimeout(service, listener, CAPTURE_WATCHDOG_MS, "capture");
                if (!service.startListening(new BufferedSpeechService.Listener() {
                    @Override public void onCaptureStopped(boolean speechDetected) {
                        Log.i(TAG, "Zipformer source capture stopped; speech=" + speechDetected);
                        try {
                            listener.endOfSpeech();
                        } catch (Exception exception) {
                            Log.w(TAG, "Unable to report end of speech", exception);
                        }
                        scheduleRecognitionTimeout(
                                service, listener, PROCESSING_TIMEOUT_MS, "processing");
                    }

                    @Override public void onPartialResult(String hypothesis) {
                        ArrayList<String> alternatives =
                                RecognitionResultParser.textsFromJson(hypothesis);
                        String text = alternatives.isEmpty() ? "" : alternatives.get(0);
                        if (!text.isEmpty()) {
                            latestAlternatives.set(alternatives);
                            sendPartial(listener, text);
                        }
                    }

                    @Override public void onResult(String hypothesis) {
                        onPartialResult(hypothesis);
                    }

                    @Override public void onFinalResult(String hypothesis) {
                        cancelRecognitionTimers();
                        closeSpeechService(service, sessionDecoder);
                        ArrayList<String> alternatives =
                                RecognitionResultParser.textsFromJson(hypothesis);
                        if (alternatives.isEmpty()) alternatives = latestAlternatives.get();
                        Log.i(TAG, "Zipformer final alternatives: " + alternatives);
                        sendResult(listener, alternatives);
                    }

                    @Override public void onError(Exception exception) {
                        cancelRecognitionTimers();
                        closeSpeechService(service, sessionDecoder);
                        Log.e(TAG, "Zipformer audio error", exception);
                        reportError(listener, SpeechRecognizer.ERROR_AUDIO);
                    }

                    @Override public void onTimeout() {
                        cancelRecognitionTimers();
                        closeSpeechService(service, sessionDecoder);
                        reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    }
                })) {
                    closeSpeechService(service, sessionDecoder);
                    reportError(listener, SpeechRecognizer.ERROR_CLIENT);
                    return;
                }
                Log.i(TAG, "Zipformer recognizer started at 16000 Hz");
            } catch (Exception | UnsatisfiedLinkError exception) {
                Log.e(TAG, "Unable to start Zipformer recognizer", exception);
                if (decoderOwner != null) closeDecoder(decoderOwner);
                reportError(listener, SpeechRecognizer.ERROR_CLIENT);
            }
        });
    }

    @Override
    protected void onStopListening(Callback listener) {
        cancelRecognitionTimers();
        BufferedSpeechService service = speechService;
        if (service != null) service.stop();
    }

    @Override
    protected void onCancel(Callback listener) {
        cancelRecognitionTimers();
        closeSpeechService(speechService);
    }

    @Override
    public void onDestroy() {
        cancelRecognitionTimers();
        closeSpeechService(speechService);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRecognitionTimeout(
            BufferedSpeechService service, Callback listener, long delayMs, String phase) {
        Runnable previous = recognitionTimeout;
        if (previous != null) mainHandler.removeCallbacks(previous);
        Runnable timeout = () -> {
            if (speechService == service) {
                Log.i(TAG, "Zipformer " + phase + " timeout");
                closeSpeechService(service);
                reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            }
        };
        recognitionTimeout = timeout;
        mainHandler.postDelayed(timeout, delayMs);
    }

    private void closeSpeechService(BufferedSpeechService service) {
        closeSpeechService(service, activeDecoder);
    }

    private void closeSpeechService(
            BufferedSpeechService service, CloseOnce<StreamingSpeechDecoder> decoderOwner) {
        if (service == null) return;
        if (speechService == service) speechService = null;
        try {
            service.cancel();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to cancel Zipformer recognizer", exception);
        }
        try {
            service.shutdown();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to release Zipformer AudioRecord", exception);
        }
        if (activeDecoder == decoderOwner) activeDecoder = null;
        closeDecoder(decoderOwner);
    }

    private static void closeDecoder(CloseOnce<StreamingSpeechDecoder> decoderOwner) {
        if (decoderOwner == null) return;
        try {
            decoderOwner.close();
        } catch (Exception exception) {
            Log.w(TAG, "Unable to release Zipformer stream", exception);
        }
    }

    private void cancelRecognitionTimers() {
        Runnable timeout = recognitionTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        recognitionTimeout = null;
    }

    private static void sendPartial(Callback callback, String text) {
        if (text.isEmpty()) return;
        Log.i(TAG, "Zipformer partial: " + text);
        Bundle results = new Bundle();
        ArrayList<String> values = new ArrayList<>();
        values.add(text);
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, values);
        try {
            callback.partialResults(results);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to send partial result", exception);
        }
    }

    private static void sendResult(Callback callback, ArrayList<String> alternatives) {
        Bundle results = new Bundle();
        ArrayList<String> values = new ArrayList<>(alternatives);
        if (values.isEmpty()) values.add("");
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, values);
        try {
            callback.results(results);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to send result", exception);
        }
    }

    private static void reportError(Callback callback, int error) {
        try {
            callback.error(error);
        } catch (Exception exception) {
            Log.w(TAG, "Unable to report recognition error", exception);
        }
    }
}
