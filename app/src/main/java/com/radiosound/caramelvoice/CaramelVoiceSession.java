package com.radiosound.caramelvoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class CaramelVoiceSession extends VoiceInteractionSession {
    private static final String TAG = "CaramelVoice";
    private static final String ESPEAK_ENGINE = "com.reecedunn.espeak";
    private static final String[] OSMAND_PACKAGES = {
            "net.osmand.dev",
            "net.osmand.plus",
            "net.osmand"
    };

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;

    CaramelVoiceSession(Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The product includes this engine as a system package. Naming it here
        // also keeps the assistant's own response path working in a sideloaded
        // smoke test where AOSP cannot yet select a non-system TTS package.
        tts = new TextToSpeech(context, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) { }
                    @Override public void onError(String utteranceId) { }
                });
            } else {
                Log.w(TAG, "No TTS engine initialized; install an offline engine");
            }
        }, ESPEAK_ENGINE);
    }

    @Override
    public View onCreateContentView() {
        statusView = new TextView(context);
        statusView.setTextSize(20);
        statusView.setTextColor(0xffffffff);
        statusView.setBackgroundColor(0xdd202124);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(40, 32, 40, 32);
        statusView.setText("Caramel Voice");
        return statusView;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        updateStatus("Listening…");
        startRecognition();
    }

    @Override
    public void onHide() {
        stopRecognition();
        super.onHide();
    }

    @Override
    public void onDestroy() {
        stopRecognition();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }

    private void startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateStatus("Offline recognition is unavailable");
            speak("Offline recognition is unavailable");
            return;
        }

        ComponentName service = new ComponentName(context, VoskRecognitionService.class);
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, service);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { updateStatus("Listening…"); }
            @Override public void onBeginningOfSpeech() { updateStatus("Listening…"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { updateStatus("Processing…"); }
            @Override public void onError(int error) {
                Log.w(TAG, "Recognition error: " + error);
                updateStatus("Recognition error " + error);
                speak("I could not understand that");
                finish();
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> values = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String phrase = values == null || values.isEmpty() ? "" : values.get(0);
                handleCommand(phrase);
            }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> values = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (values != null && !values.isEmpty()) updateStatus(values.get(0));
            }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        request.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        recognizer.startListening(request);
    }

    private void stopRecognition() {
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void handleCommand(String phrase) {
        String normalized = phrase == null ? "" : phrase.toLowerCase(Locale.US).trim();
        if (normalized.isEmpty()) {
            speak("I did not hear a command");
            finish();
            return;
        }

        String response;
        if (normalized.contains("what time") || normalized.equals("time")) {
            response = "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        } else if (normalized.contains("navigate home") || normalized.contains("take me home")) {
            response = launchOsmAndSearch("home")
                    ? "Opening home in OsmAnd."
                    : "OsmAnd is not installed.";
            Log.i(TAG, "NAVIGATE_HOME: " + phrase);
        } else if (normalized.startsWith("navigate to ")
                || normalized.startsWith("take me to ")) {
            String destination = normalized.startsWith("navigate to ")
                    ? normalized.substring("navigate to ".length()).trim()
                    : normalized.substring("take me to ".length()).trim();
            boolean launched = !destination.isEmpty() && launchOsmAndSearch(destination);
            response = launched
                    ? "Opening navigation for " + destination + "."
                    : "OsmAnd is not installed.";
            Log.i(TAG, "NAVIGATE_TO: " + destination);
        } else if (normalized.startsWith("open map") || normalized.equals("show map")) {
            response = launchOsmAnd(null)
                    ? "Opening the map."
                    : "OsmAnd is not installed.";
        } else if (normalized.startsWith("play ")) {
            response = "Media command recognized; media routing is next to wire in.";
            Log.i(TAG, "MEDIA_COMMAND: " + phrase);
        } else {
            response = "I heard: " + phrase;
        }

        updateStatus(response);
        speak(response);
        mainHandler.postDelayed(this::finish, 1200);
    }

    private boolean launchOsmAndSearch(String destination) {
        return launchOsmAnd(Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
    }

    private boolean launchOsmAnd(Uri uri) {
        for (String packageName : OSMAND_PACKAGES) {
            try {
                Intent launch;
                if (uri == null) {
                    launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
                } else {
                    launch = new Intent(Intent.ACTION_VIEW, uri).setPackage(packageName);
                }
                if (launch != null) {
                    startAssistantActivity(launch);
                    return true;
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to launch OsmAnd package " + packageName, exception);
            }
        }
        return false;
    }

    private void speak(String text) {
        if (ttsReady && tts != null) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "caramel-voice");
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "caramel-voice");
        } else {
            Log.w(TAG, "TTS unavailable: " + text);
        }
    }

    private void updateStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }
}
