package com.radiosound.caramelvoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
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
    private static final String OSMAND_NAVIGATE_ACTION =
            "androidx.car.app.action.NAVIGATE";
    private static final String[] OSMAND_PACKAGES = {
            "net.osmand.dev",
            "net.osmand.plus",
            "net.osmand"
    };
    private static final int MAX_RECOGNITION_ROUTE_RETRIES = 3;
    private static final long RECOGNITION_ROUTE_RETRY_DELAY_MS = 750;
    private static final long MODEL_READY_TIMEOUT_MS = 30000;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MediaCommandController mediaController;
    private TextView statusView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int utteranceSequence;
    private String activeUtteranceId;
    private Runnable utteranceCompletionAction;
    private Runnable utteranceTimeout;
    private Runnable recognitionRetry;
    private Runnable modelReadyTimeout;
    private int recognitionRouteRetries;
    private int showGeneration;
    private boolean sessionVisible;

    CaramelVoiceSession(Context context) {
        super(context);
        this.context = context;
        mediaController = new MediaCommandController(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TextToSpeech.OnInitListener listener = status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                Log.i(TAG, "TTS engine initialized: " + tts.getDefaultEngine());
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        Log.i(TAG, "TTS started: " + utteranceId);
                    }

                    @Override public void onDone(String utteranceId) {
                        Log.i(TAG, "TTS completed: " + utteranceId);
                        completeUtterance(utteranceId);
                    }

                    @Override public void onError(String utteranceId) {
                        Log.w(TAG, "TTS failed: " + utteranceId);
                        completeUtterance(utteranceId);
                    }
                });
            } else {
                Log.w(TAG, "No TTS engine initialized; install an offline engine");
            }
        };
        String selectedEngine = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);
        if (selectedEngine == null || selectedEngine.isEmpty()) {
            tts = new TextToSpeech(context, listener);
        } else {
            Log.i(TAG, "Requesting TTS engine: " + selectedEngine);
            tts = new TextToSpeech(context, listener, selectedEngine);
        }
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
        sessionVisible = true;
        int generation = ++showGeneration;
        recognitionRouteRetries = 0;
        RecognitionContextRepository.refreshForeground(context);
        cancelModelReadyTimeout();
        updateStatus("Preparing microphone…");
        modelReadyTimeout = () -> {
            if (!sessionVisible || generation != showGeneration) return;
            modelReadyTimeout = null;
            Log.w(TAG, "Recognition model did not become ready before timeout");
            updateStatus("Offline recognition unavailable");
            speak("Offline recognition is unavailable");
        };
        mainHandler.postDelayed(modelReadyTimeout, MODEL_READY_TIMEOUT_MS);
        RecognitionModelReadiness.whenReady(context, available -> mainHandler.post(() -> {
            if (!sessionVisible || generation != showGeneration) return;
            cancelModelReadyTimeout();
            if (!available) {
                Log.w(TAG, "Recognition model is unavailable");
                updateStatus("Offline recognition unavailable");
                speak("Offline recognition is unavailable");
                return;
            }
            updateStatus("Listening…");
            startRecognition();
        }));
    }

    @Override
    public void onHide() {
        sessionVisible = false;
        showGeneration++;
        cancelModelReadyTimeout();
        cancelRecognitionRetry();
        stopRecognition();
        super.onHide();
    }

    @Override
    public void onDestroy() {
        sessionVisible = false;
        showGeneration++;
        cancelModelReadyTimeout();
        cancelRecognitionRetry();
        stopRecognition();
        mediaController.close();
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        activeUtteranceId = null;
        utteranceCompletionAction = null;
        super.onDestroy();
    }

    private void startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            updateStatus("Offline recognition is unavailable");
            speak("Offline recognition is unavailable");
            return;
        }

        Class<?> serviceClass = RecognitionBackendProfile.load(context).engine
                == RecognitionBackendProfile.Engine.ZIPFORMER
                ? SherpaRecognitionService.class : VoskRecognitionService.class;
        ComponentName service = new ComponentName(context, serviceClass);
        recognizer = SpeechRecognizer.createSpeechRecognizer(context, service);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { updateStatus("Listening…"); }
            @Override public void onBeginningOfSpeech() { updateStatus("Listening…"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { updateStatus("Processing…"); }
            @Override public void onError(int error) {
                Log.w(TAG, "Recognition error: " + error);
                if (error == SpeechRecognizer.ERROR_CLIENT
                        && scheduleRecognitionRouteRetry()) {
                    return;
                }
                updateStatus("Recognition error " + error);
                speak("I could not understand that");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> values = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                handleCommand(values);
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
        request.putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true);
        request.putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                new ArrayList<>(RecognitionContextRepository.snapshot(context).hotwordPhrases()));
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

    private boolean scheduleRecognitionRouteRetry() {
        if (recognitionRouteRetries >= MAX_RECOGNITION_ROUTE_RETRIES) {
            Log.w(TAG, "Microphone route unavailable after "
                    + MAX_RECOGNITION_ROUTE_RETRIES + " retries");
            updateStatus("Microphone unavailable");
            speak("The microphone is unavailable");
            return false;
        }

        int attempt = ++recognitionRouteRetries;
        Log.i(TAG, "Retrying microphone route, attempt " + attempt + "/"
                + MAX_RECOGNITION_ROUTE_RETRIES);
        updateStatus("Waiting for microphone…");
        mainHandler.post(() -> {
            if (!sessionVisible) return;
            stopRecognition();
            cancelRecognitionRetry();
            recognitionRetry = () -> {
                recognitionRetry = null;
                if (sessionVisible) startRecognition();
            };
            mainHandler.postDelayed(recognitionRetry, RECOGNITION_ROUTE_RETRY_DELAY_MS);
        });
        return true;
    }

    private void cancelRecognitionRetry() {
        if (recognitionRetry != null) {
            mainHandler.removeCallbacks(recognitionRetry);
            recognitionRetry = null;
        }
    }

    private void cancelModelReadyTimeout() {
        if (modelReadyTimeout != null) {
            mainHandler.removeCallbacks(modelReadyTimeout);
            modelReadyTimeout = null;
        }
    }

    private void handleCommand(ArrayList<String> alternatives) {
        VoiceCommandRouter.Command command = VoiceCommandRouter.routeBest(alternatives);
        String phrase = command.phrase;
        if (command.type == VoiceCommandRouter.Type.EMPTY) {
            speak("I did not hear a command");
            return;
        }

        // Proper names can leave the ASR as an otherwise non-actionable sentence even when a
        // distinctive catalog token survived (for example, "there's opus and"). Let the
        // app-neutral context index recover the media entity; no provider or title is hardcoded.
        if (command.type == VoiceCommandRouter.Type.ECHO) {
            String contextualMedia = RecognitionContextRepository.snapshot(context).resolve(
                    RecognitionEntity.Domain.MEDIA, phrase);
            if (RecognitionContextIndex.hasDistinctiveToken(phrase)
                    && !contextualMedia.isEmpty()
                    && !RecognitionContextIndex.normalize(contextualMedia)
                            .equals(RecognitionContextIndex.normalize(phrase))) {
                command = VoiceCommandRouter.playFromContext(contextualMedia, phrase);
                Log.i(TAG, "Recovered contextual media command: " + phrase
                        + " -> " + contextualMedia);
            }
        }

        String response;
        Runnable afterSpeech = null;
        if (command.type == VoiceCommandRouter.Type.TIME) {
            response = "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        } else if (command.type == VoiceCommandRouter.Type.NAVIGATE_HOME) {
            Intent launch = findOsmAndSearchIntent("home");
            response = launch != null
                    ? "Opening home in OsmAnd."
                    : "OsmAnd is not installed.";
            if (launch != null) {
                afterSpeech = () -> startOsmAndActivity(launch);
            }
            Log.i(TAG, "NAVIGATE_HOME: " + phrase);
        } else if (command.type == VoiceCommandRouter.Type.NAVIGATE_TO) {
            String destination = RecognitionContextRepository.snapshot(context).resolve(
                    RecognitionEntity.Domain.NAVIGATION, command.argument);
            Intent launch = destination.isEmpty() ? null : findOsmAndSearchIntent(destination);
            response = launch != null
                    ? "Opening navigation for " + destination + "."
                    : "OsmAnd is not installed.";
            Log.i(TAG, "NAVIGATE_TO: " + destination);
            if (launch != null) {
                afterSpeech = () -> {
                    if (startOsmAndActivity(launch)) {
                        RecognitionContextRepository.recordSuccessful(
                                context, RecognitionEntity.Domain.NAVIGATION, destination);
                    }
                };
            }
        } else if (command.type == VoiceCommandRouter.Type.OPEN_MAP) {
            Intent launch = findOsmAndIntent(null);
            response = launch != null
                    ? "Opening the map."
                    : "OsmAnd is not installed.";
            if (launch != null) {
                afterSpeech = () -> startOsmAndActivity(launch);
            }
        } else if (command.type == VoiceCommandRouter.Type.PLAY) {
            handleMediaCommand(command);
            return;
        } else {
            response = "I heard: " + command.phrase;
        }

        updateStatus(response);
        Log.i(TAG, "COMMAND: " + phrase + " -> " + response);
        speak(response, afterSpeech);
    }

    private void handleMediaCommand(VoiceCommandRouter.Command command) {
        String query = RecognitionContextRepository.snapshot(context).resolve(
                RecognitionEntity.Domain.MEDIA, command.argument);
        if (query.isEmpty()) {
            updateStatus("Tell me what to play");
            speak("Tell me what to play");
            return;
        }

        updateStatus("Searching your media apps for " + query + "…");
        Log.i(TAG, "MEDIA_COMMAND: " + command.phrase + " -> " + query);
        mediaController.playFromSearch(query, (result, playerPackage) -> {
            String response;
            if (result == MediaCommandController.Result.STARTED) {
                String player = mediaPlayerLabel(playerPackage);
                response = "Playing " + query + " on " + player + ".";
                RecognitionContextRepository.recordSuccessful(
                        context, RecognitionEntity.Domain.MEDIA, query);
            } else if (result == MediaCommandController.Result.PLAYER_NOT_FOUND) {
                response = "No compatible media player is available.";
            } else {
                response = "I could not start that in your media app.";
            }
            updateStatus(response);
            Log.i(TAG, "MEDIA_RESULT: " + query + " -> " + result
                    + " (" + playerPackage + ")");
            speak(response);
        });
    }

    private String mediaPlayerLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "your media app";
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            String label = RecognitionEntity.cleanPhrase(
                    packageManager.getApplicationLabel(applicationInfo).toString());
            return label.isEmpty() ? "your media app" : label;
        } catch (PackageManager.NameNotFoundException exception) {
            return "your media app";
        }
    }

    private Intent findOsmAndSearchIntent(String destination) {
        return findOsmAndIntent(Uri.parse("geo:0,0?q=" + Uri.encode(destination)));
    }

    private Intent findOsmAndIntent(Uri uri) {
        for (String packageName : OSMAND_PACKAGES) {
            try {
                Intent launch;
                if (uri == null) {
                    launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
                } else {
                    // OsmAnd reserves its Car App entry point for this action. A generic
                    // ACTION_VIEW geo intent resolves to the full-screen GeoIntentActivity.
                    launch = new Intent(OSMAND_NAVIGATE_ACTION, uri)
                            .addCategory(Intent.CATEGORY_DEFAULT)
                            .setPackage(packageName);
                }
                if (launch != null) {
                    return launch;
                }
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to launch OsmAnd package " + packageName, exception);
            }
        }
        return null;
    }

    private void speak(String text) {
        speak(text, null);
    }

    private void speak(String text, Runnable afterSpeech) {
        VoiceResponseCoordinator.respond(text, this::speakNow, afterSpeech);
    }

    private void speakNow(String text, Runnable afterSpeech) {
        String utteranceId = "caramel-voice-" + (++utteranceSequence);
        activeUtteranceId = utteranceId;
        utteranceCompletionAction = afterSpeech;
        if (ttsReady && tts != null) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS rejected utterance " + utteranceId + ": " + result);
                completeUtterance(utteranceId);
                return;
            }
            Runnable timeout = () -> {
                Log.w(TAG, "TTS completion timeout: " + utteranceId);
                completeUtterance(utteranceId);
            };
            utteranceTimeout = timeout;
            mainHandler.postDelayed(timeout, VoiceResponseCoordinator.timeoutMs(afterSpeech));
        } else {
            Log.w(TAG, "TTS unavailable: " + text);
            completeUtterance(utteranceId);
        }
    }

    private boolean startOsmAndActivity(Intent launch) {
        try {
            startAssistantActivity(launch);
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to start OsmAnd activity", exception);
            return false;
        }
    }

    private void completeUtterance(String utteranceId) {
        mainHandler.post(() -> {
            if (!utteranceId.equals(activeUtteranceId)) return;
            activeUtteranceId = null;
            if (utteranceTimeout != null) {
                mainHandler.removeCallbacks(utteranceTimeout);
                utteranceTimeout = null;
            }
            Runnable afterSpeech = utteranceCompletionAction;
            utteranceCompletionAction = null;
            if (afterSpeech != null) {
                try {
                    afterSpeech.run();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Command action failed after TTS", exception);
                }
            }
            mainHandler.postDelayed(this::finish, 200);
        });
    }

    private void updateStatus(String text) {
        if (statusView != null) statusView.setText(text);
    }
}
