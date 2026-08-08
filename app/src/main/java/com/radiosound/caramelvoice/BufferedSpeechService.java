/*
 * Copyright 2019 Alpha Cephei Inc.
 * Modifications Copyright 2026 Radio Sound, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.radiosound.caramelvoice;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Microphone loop that drains AudioRecord independently of decoding.
 *
 * <p>Some recognizers record and decode on one thread. A cold decoder on a Raspberry Pi 5
 * can take longer than AudioRecord's
 * native buffer, which makes AudioFlinger discard microphone frames. This
 * implementation keeps a dedicated capture thread and sends 100 ms chunks
 * through a queue to the decoder. Source-side voice activity detection removes
 * idle pre-roll and excess trailing silence before they reach a decoder that is
 * slower than real time on the 4 GB Pi. The queue is intentionally unbounded,
 * but the detector caps an utterance at ten seconds (about 320 KiB of PCM).</p>
 */
final class BufferedSpeechService {
    private static final String TAG = "CaramelVoice";
    private static final float READ_CHUNK_SECONDS = 0.1f;
    private static final float AUDIO_RECORD_BUFFER_SECONDS = 2.0f;
    private static final int PRE_ROLL_CHUNKS = 3;
    private static final int TRAILING_AUDIO_CHUNKS = 3;
    private static final short[] END_OF_AUDIO = new short[0];

    interface Listener {
        void onCaptureStopped(boolean speechDetected);
        void onPartialResult(String hypothesis);
        void onResult(String hypothesis);
        void onFinalResult(String hypothesis);
        void onError(Exception exception);
        void onTimeout();
    }

    private final StreamingSpeechDecoder decoder;
    private final int readChunkSamples;
    private final AudioRecord recorder;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>();
    private final AtomicReference<Exception> terminalFailure = new AtomicReference<>();
    private final AtomicBoolean terminalCallbackPosted = new AtomicBoolean();
    private final AtomicBoolean recorderReleased = new AtomicBoolean();

    private volatile Thread captureThread;
    private volatile Thread decoderThread;
    private volatile boolean cancelRequested;
    private volatile boolean stopRequested;

    @SuppressLint("MissingPermission")
    BufferedSpeechService(StreamingSpeechDecoder decoder, float sampleRate) throws IOException {
        this.decoder = decoder;
        int sampleRateHz = Math.round(sampleRate);
        readChunkSamples = Math.round(sampleRateHz * READ_CHUNK_SECONDS);
        int desiredBufferBytes = Math.round(
                sampleRateHz * AUDIO_RECORD_BUFFER_SECONDS * Short.BYTES);
        int minimumBufferBytes = AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int audioRecordBufferBytes = Math.max(
                desiredBufferBytes,
                minimumBufferBytes > 0 ? minimumBufferBytes : 0);

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioRecordBufferBytes);
        if (recorder.getState() == AudioRecord.STATE_UNINITIALIZED) {
            recorder.release();
            recorderReleased.set(true);
            throw new IOException(
                    "Failed to initialize recorder; the microphone may already be in use");
        }
        Log.i(TAG, "Speech AudioRecord buffer: " + audioRecordBufferBytes
                + " bytes; capture chunk: " + readChunkSamples + " samples");
    }

    synchronized boolean startListening(Listener listener) {
        if (captureThread != null || decoderThread != null || recorderReleased.get()) {
            return false;
        }
        cancelRequested = false;
        stopRequested = false;
        terminalFailure.set(null);
        terminalCallbackPosted.set(false);
        audioQueue.clear();

        Thread decoder = new Thread(() -> decode(listener), "CaramelSpeechDecode");
        Thread capture = new Thread(() -> capture(listener), "CaramelSpeechCapture");
        decoderThread = decoder;
        captureThread = capture;
        decoder.start();
        capture.start();
        return true;
    }

    /** Stop capture and let the decoder drain queued speech before finalizing. */
    boolean stop() {
        synchronized (this) {
            if (captureThread == null && decoderThread == null) return false;
            stopRequested = true;
        }
        return true;
    }

    /** Cancel capture and decoding without delivering a final recognition result. */
    boolean cancel() {
        Thread capture;
        Thread decoder;
        synchronized (this) {
            capture = captureThread;
            decoder = decoderThread;
            if (capture == null && decoder == null) return false;
            cancelRequested = true;
            stopRequested = true;
            audioQueue.clear();
            audioQueue.offer(END_OF_AUDIO);
        }

        stopRecorder();
        if (capture != null) capture.interrupt();
        if (decoder != null) decoder.interrupt();
        joinThread(capture);
        joinThread(decoder);
        return true;
    }

    void shutdown() {
        cancel();
        if (recorderReleased.compareAndSet(false, true)) recorder.release();
    }

    private void capture(Listener listener) {
        VoiceActivityDetector detector = new VoiceActivityDetector();
        Deque<short[]> preRoll = new ArrayDeque<>();
        Deque<short[]> pendingSilence = new ArrayDeque<>();
        boolean speechDetected = false;
        boolean trailingAudioQueued = false;
        try {
            if (stopRequested) return;
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException(
                        "Failed to start recording; the microphone may already be in use");
            }

            short[] buffer = new short[readChunkSamples];
            while (!stopRequested) {
                // The three-argument overload blocks until audio is available and
                // keeps this helper compatible with Vosk Android's API 21 floor.
                int samplesRead = recorder.read(buffer, 0, buffer.length);
                if (samplesRead < 0) {
                    if (stopRequested) break;
                    throw new IOException("AudioRecord read failed: " + samplesRead);
                }
                if (samplesRead > 0) {
                    short[] chunk = Arrays.copyOf(buffer, samplesRead);
                    VoiceActivityDetector.Event event = detector.accept(chunk, samplesRead);
                    if (!speechDetected) {
                        preRoll.offerLast(chunk);
                        while (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.removeFirst();
                        if (event == VoiceActivityDetector.Event.SPEECH_STARTED) {
                            speechDetected = true;
                            Log.i(TAG, "Speech source VAD speech start: level="
                                    + roundedDb(detector.getLastLevelDbfs())
                                    + " dBFS threshold="
                                    + roundedDb(detector.getThresholdDbfs()) + " dBFS");
                            enqueueAll(preRoll);
                        } else if (event == VoiceActivityDetector.Event.NO_SPEECH_TIMEOUT) {
                            Log.i(TAG, "Speech source VAD no-speech timeout");
                            break;
                        }
                    } else if (event == VoiceActivityDetector.Event.QUIET) {
                        pendingSilence.offerLast(chunk);
                    } else if (event == VoiceActivityDetector.Event.END_OF_SPEECH) {
                        pendingSilence.offerLast(chunk);
                        enqueueFirst(pendingSilence, TRAILING_AUDIO_CHUNKS);
                        trailingAudioQueued = true;
                        Log.i(TAG, "Speech source VAD end: level="
                                + roundedDb(detector.getLastLevelDbfs())
                                + " dBFS threshold="
                                + roundedDb(detector.getThresholdDbfs()) + " dBFS");
                        break;
                    } else {
                        enqueueAll(pendingSilence);
                        audioQueue.put(chunk);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            if (!cancelRequested && !stopRequested) {
                terminalFailure.compareAndSet(null, exception);
                postError(listener, exception);
            }
        } finally {
            stopRecorder();
            if (speechDetected && !trailingAudioQueued) {
                try {
                    enqueueFirst(pendingSilence, TRAILING_AUDIO_CHUNKS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!cancelRequested && terminalFailure.get() == null) {
                boolean finalSpeechDetected = speechDetected;
                mainHandler.post(() -> listener.onCaptureStopped(finalSpeechDetected));
            }
            audioQueue.offer(END_OF_AUDIO);
            synchronized (this) {
                if (captureThread == Thread.currentThread()) captureThread = null;
            }
        }
    }

    private void decode(Listener listener) {
        try {
            while (!cancelRequested) {
                short[] buffer = audioQueue.take();
                if (buffer == END_OF_AUDIO) break;

                StreamingSpeechDecoder.Result result =
                        decoder.acceptWaveform(buffer, buffer.length);
                if (result.segmentComplete) {
                    mainHandler.post(() -> listener.onResult(result.hypothesis));
                } else {
                    mainHandler.post(() -> listener.onPartialResult(result.hypothesis));
                }
            }

            if (!cancelRequested && terminalFailure.get() == null) {
                String finalResult = decoder.finish();
                if (terminalCallbackPosted.compareAndSet(false, true)) {
                    mainHandler.post(() -> listener.onFinalResult(finalResult));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            terminalFailure.compareAndSet(null, exception);
            stopRequested = true;
            postError(listener, exception);
        } finally {
            synchronized (this) {
                if (decoderThread == Thread.currentThread()) decoderThread = null;
            }
        }
    }

    private void postError(Listener listener, Exception exception) {
        if (!cancelRequested && terminalCallbackPosted.compareAndSet(false, true)) {
            mainHandler.post(() -> listener.onError(exception));
        }
    }

    private void stopRecorder() {
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
            }
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Unable to stop Vosk AudioRecord", exception);
        }
    }

    private void enqueueAll(Deque<short[]> chunks) throws InterruptedException {
        while (!chunks.isEmpty()) audioQueue.put(chunks.removeFirst());
    }

    private void enqueueFirst(Deque<short[]> chunks, int limit) throws InterruptedException {
        for (int index = 0; index < limit && !chunks.isEmpty(); index++) {
            audioQueue.put(chunks.removeFirst());
        }
        chunks.clear();
    }

    private static double roundedDb(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void joinThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
