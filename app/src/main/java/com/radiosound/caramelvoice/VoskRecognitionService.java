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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class VoskRecognitionService extends RecognitionService {
    private static final String TAG = "CaramelVoice";
    private static final long CAPTURE_WATCHDOG_MS = 25000;
    private static final long PROCESSING_TIMEOUT_MS = 20000;
    private static final int MAX_ALTERNATIVES = 5;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile BufferedSpeechService speechService;
    private volatile Recognizer activeRecognizer;
    private volatile Runnable recognitionTimeout;

    @Override
    public void onCreate() {
        super.onCreate();
        VoskModelRepository.preload(this);
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        executor.execute(() -> {
            try {
                Model loadedModel = VoskModelRepository.await(this, 30, TimeUnit.SECONDS);
                if (loadedModel == null) {
                    reportError(listener, SpeechRecognizer.ERROR_SERVER);
                    return;
                }
                listener.readyForSpeech(new Bundle());
                Recognizer recognizer = new Recognizer(loadedModel, 16000.0f);
                recognizer.setMaxAlternatives(MAX_ALTERNATIVES);
                activeRecognizer = recognizer;
                BufferedSpeechService service = new BufferedSpeechService(recognizer, 16000.0f);
                speechService = service;
                AtomicReference<ArrayList<String>> latestAlternatives =
                        new AtomicReference<>(new ArrayList<>());
                scheduleRecognitionTimeout(
                        service, listener, CAPTURE_WATCHDOG_MS, "capture");
                if (!service.startListening(new BufferedSpeechService.Listener() {
                    @Override public void onCaptureStopped(boolean speechDetected) {
                        Log.i(TAG, "Vosk source capture stopped; speech=" + speechDetected);
                        try {
                            listener.endOfSpeech();
                        } catch (Exception exception) {
                            Log.w(TAG, "Unable to report end of speech", exception);
                        }
                        scheduleRecognitionTimeout(
                                service, listener, PROCESSING_TIMEOUT_MS, "processing");
                    }

                    @Override public void onPartialResult(String hypothesis) {
                        ArrayList<String> alternatives = textsFromJson(hypothesis);
                        String text = alternatives.isEmpty() ? "" : alternatives.get(0);
                        if (!text.isEmpty()) {
                            latestAlternatives.set(alternatives);
                            sendPartial(listener, text);
                        }
                    }

                    @Override public void onResult(String hypothesis) {
                        ArrayList<String> alternatives = textsFromJson(hypothesis);
                        String text = alternatives.isEmpty() ? "" : alternatives.get(0);
                        if (!text.isEmpty()) {
                            latestAlternatives.set(alternatives);
                            sendPartial(listener, text);
                        }
                    }

                    @Override public void onFinalResult(String hypothesis) {
                        cancelRecognitionTimers();
                        closeSpeechService(service, recognizer);
                        ArrayList<String> alternatives = textsFromJson(hypothesis);
                        if (alternatives.isEmpty()) alternatives = latestAlternatives.get();
                        Log.i(TAG, "Vosk final alternatives: " + alternatives);
                        sendResult(listener, alternatives);
                    }

                    @Override public void onError(Exception exception) {
                        cancelRecognitionTimers();
                        closeSpeechService(service, recognizer);
                        Log.e(TAG, "Vosk audio error", exception);
                        reportError(listener, SpeechRecognizer.ERROR_AUDIO);
                    }

                    @Override public void onTimeout() {
                        cancelRecognitionTimers();
                        closeSpeechService(service, recognizer);
                        reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                    }
                })) {
                    closeSpeechService(service, recognizer);
                    reportError(listener, SpeechRecognizer.ERROR_CLIENT);
                    return;
                }
                Log.i(TAG, "Vosk recognizer started at 16000 Hz");
            } catch (Exception exception) {
                Log.e(TAG, "Unable to start Vosk recognizer", exception);
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
        BufferedSpeechService service = speechService;
        closeSpeechService(service);
    }

    @Override
    public void onDestroy() {
        cancelRecognitionTimers();
        BufferedSpeechService service = speechService;
        closeSpeechService(service);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRecognitionTimeout(
            BufferedSpeechService service, Callback listener, long delayMs, String phase) {
        Runnable previous = recognitionTimeout;
        if (previous != null) mainHandler.removeCallbacks(previous);
        Runnable timeout = () -> {
            if (speechService == service) {
                Log.i(TAG, "Vosk " + phase + " timeout");
                closeSpeechService(service);
                reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
            }
        };
        recognitionTimeout = timeout;
        mainHandler.postDelayed(timeout, delayMs);
    }

    /** Stop the recognizer thread and release its AudioRecord on every terminal path. */
    private void closeSpeechService(BufferedSpeechService service) {
        closeSpeechService(service, activeRecognizer);
    }

    private void closeSpeechService(BufferedSpeechService service, Recognizer recognizer) {
        if (service == null) return;
        if (speechService == service) speechService = null;
        try {
            service.cancel();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to cancel Vosk recognizer", exception);
        }
        try {
            service.shutdown();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to release Vosk AudioRecord", exception);
        }
        if (activeRecognizer == recognizer) activeRecognizer = null;
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to release Vosk recognizer", exception);
            }
        }
    }

    private void cancelRecognitionTimers() {
        Runnable timeout = recognitionTimeout;
        if (timeout != null) mainHandler.removeCallbacks(timeout);
        recognitionTimeout = null;
    }

    private static ArrayList<String> textsFromJson(String value) {
        ArrayList<String> texts = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(value == null ? "{}" : value);
            JSONArray alternatives = json.optJSONArray("alternatives");
            if (alternatives != null) {
                for (int index = 0; index < alternatives.length(); index++) {
                    JSONObject alternative = alternatives.optJSONObject(index);
                    if (alternative != null) {
                        addUniqueText(texts, alternative.optString("text", ""));
                    }
                }
            }
            addUniqueText(texts, json.optString("text", ""));
            addUniqueText(texts, json.optString("partial", ""));
        } catch (Exception exception) {
            addUniqueText(texts, value);
        }
        return texts;
    }

    private static void addUniqueText(ArrayList<String> texts, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty() && !texts.contains(text)) texts.add(text);
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
