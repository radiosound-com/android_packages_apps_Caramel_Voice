package com.radiosound.caramelvoice;

import android.content.Intent;
import android.os.Bundle;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CountDownLatch modelLoadComplete = new CountDownLatch(1);
    private volatile Model model;
    private volatile SpeechService speechService;

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
                service.startListening(new RecognitionListener() {
                    @Override public void onPartialResult(String hypothesis) {
                        sendPartial(listener, textFromJson(hypothesis));
                    }

                    @Override public void onResult(String hypothesis) {
                        sendPartial(listener, textFromJson(hypothesis));
                    }

                    @Override public void onFinalResult(String hypothesis) {
                        try {
                            listener.endOfSpeech();
                        } catch (Exception exception) {
                            Log.w(TAG, "Unable to report end of speech", exception);
                        }
                        sendResult(listener, textFromJson(hypothesis));
                    }

                    @Override public void onError(Exception exception) {
                        Log.e(TAG, "Vosk audio error", exception);
                        reportError(listener, SpeechRecognizer.ERROR_AUDIO);
                    }

                    @Override public void onTimeout() {
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
        SpeechService service = speechService;
        if (service != null) service.stop();
    }

    @Override
    protected void onCancel(Callback listener) {
        SpeechService service = speechService;
        speechService = null;
        if (service != null) {
            service.cancel();
            service.shutdown();
        }
    }

    @Override
    public void onDestroy() {
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
        if (text.isEmpty()) return;
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
