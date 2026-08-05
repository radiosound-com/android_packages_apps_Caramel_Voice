package com.radiosound.caramelvoice;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VoskRecognitionService extends RecognitionService {
    private static final String TAG = "CaramelVoice";
    private static final String MODEL_ASSET = "vosk-model-small-en-us-0.15";
    private static final String MODEL_DIR = "vosk-model-small-en-us-0.15";
    private static final long SILENCE_TIMEOUT_MS = 900;
    private static final long LISTENING_TIMEOUT_MS = 15000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch modelLoadComplete = new CountDownLatch(1);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Model model;
    private volatile SpeechService speechService;
    private volatile Runnable silenceFinalizer;
    private volatile Runnable listeningTimeout;

    @Override
    public void onCreate() {
        super.onCreate();
        executor.execute(() -> {
            try {
                String modelPath = StorageService.sync(this, MODEL_ASSET, MODEL_DIR);
                model = new Model(modelPath);
                Log.i(TAG, "Vosk model ready at " + modelPath);
            } catch (IOException exception) {
                Log.e(TAG, "Unable to unpack Vosk model", exception);
            } finally {
                modelLoadComplete.countDown();
            }
        });
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        executor.execute(() -> {
            try {
                if (!modelLoadComplete.await(30, TimeUnit.SECONDS)) {
                    reportError(listener, SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                Model loadedModel = model;
                if (loadedModel == null) {
                    reportError(listener, SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                listener.readyForSpeech(new Bundle());
                Recognizer recognizer = new Recognizer(loadedModel, 16000.0f);
                SpeechService service = new SpeechService(recognizer, 16000.0f);
                speechService = service;
                final String[] latestText = {""};
                scheduleListeningTimeout(service, listener);
                service.startListening(new RecognitionListener() {
                    @Override public void onPartialResult(String hypothesis) {
                        String text = textFromJson(hypothesis);
                        if (!text.isEmpty()) {
                            latestText[0] = text;
                            sendPartial(listener, text);
                            scheduleSilenceFinalization(service);
                        }
                    }

                    @Override public void onResult(String hypothesis) {
                        String text = textFromJson(hypothesis);
                        if (!text.isEmpty()) {
                            latestText[0] = text;
                            sendPartial(listener, text);
                            scheduleSilenceFinalization(service);
                        }
                    }

                    @Override public void onFinalResult(String hypothesis) {
                        cancelRecognitionTimers();
                        speechService = null;
                        String text = textFromJson(hypothesis);
                        if (text.isEmpty()) text = latestText[0];
                        Log.i(TAG, "Vosk final: " + text);
                        try {
                            listener.endOfSpeech();
                        } catch (Exception exception) {
                            Log.w(TAG, "Unable to report end of speech", exception);
                        }
                        sendResult(listener, text);
                    }

                    @Override public void onError(Exception exception) {
                        cancelRecognitionTimers();
                        speechService = null;
                        Log.e(TAG, "Vosk audio error", exception);
                        reportError(listener, SpeechRecognizer.ERROR_AUDIO);
                    }

                    @Override public void onTimeout() {
                        cancelRecognitionTimers();
                        speechService = null;
                        reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    }
                });
            } catch (Exception exception) {
                Log.e(TAG, "Unable to start Vosk recognizer", exception);
                reportError(listener, SpeechRecognizer.ERROR_CLIENT);
            }
        });
    }

    @Override
    protected void onStopListening(Callback listener) {
        cancelRecognitionTimers();
        SpeechService service = speechService;
        if (service != null) service.stop();
    }

    @Override
    protected void onCancel(Callback listener) {
        cancelRecognitionTimers();
        SpeechService service = speechService;
        speechService = null;
        if (service != null) {
            service.cancel();
            service.shutdown();
        }
    }

    @Override
    public void onDestroy() {
        cancelRecognitionTimers();
        SpeechService service = speechService;
        speechService = null;
        if (service != null) {
            service.cancel();
            service.shutdown();
        }
        Model loadedModel = model;
        model = null;
        if (loadedModel != null) loadedModel.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleSilenceFinalization(SpeechService service) {
        Runnable previous = silenceFinalizer;
        if (previous != null) mainHandler.removeCallbacks(previous);
        Runnable finalizer = () -> {
            if (speechService == service) {
                Log.i(TAG, "Vosk silence timeout; finalizing");
                service.stop();
            }
        };
        silenceFinalizer = finalizer;
        mainHandler.postDelayed(finalizer, SILENCE_TIMEOUT_MS);
    }

    private void scheduleListeningTimeout(SpeechService service, Callback listener) {
        Runnable timeout = () -> {
            if (speechService == service) {
                Log.i(TAG, "Vosk listening timeout");
                service.cancel();
                speechService = null;
                reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            }
        };
        listeningTimeout = timeout;
        mainHandler.postDelayed(timeout, LISTENING_TIMEOUT_MS);
    }

    private void cancelRecognitionTimers() {
        Runnable finalizer = silenceFinalizer;
        if (finalizer != null) mainHandler.removeCallbacks(finalizer);
        silenceFinalizer = null;
        Runnable timeout = listeningTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        listeningTimeout = null;
    }

    private static String textFromJson(String value) {
        try {
            JSONObject json = new JSONObject(value == null ? "{}" : value);
            String text = json.optString("text", "").trim();
            if (!text.isEmpty()) return text;
            return json.optString("partial", "").trim();
        } catch (Exception exception) {
            return value == null ? "" : value;
        }
    }

    private static void sendPartial(Callback callback, String text) {
        if (text.isEmpty()) return;
        Log.i(TAG, "Vosk partial: " + text);
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

    private static void sendResult(Callback callback, String text) {
        Bundle results = new Bundle();
        ArrayList<String> values = new ArrayList<>();
        values.add(text);
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
