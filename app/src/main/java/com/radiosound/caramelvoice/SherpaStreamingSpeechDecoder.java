/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

import org.json.JSONObject;

import java.util.Arrays;

/** One Zipformer stream backed by a safely leased warm recognizer. */
final class SherpaStreamingSpeechDecoder implements StreamingSpeechDecoder {
    private static final int SAMPLE_RATE = 16000;
    private static final int LEFT_PADDING_SAMPLES = 4800;
    private static final int TAIL_PADDING_SAMPLES = 12800;

    private final SherpaModelRepository.Lease lease;
    private final OnlineRecognizer recognizer;
    private OnlineStream stream;
    private OnlineStream silentCompanion;
    private OnlineStream[] decodeBatch;
    private float[] floatBuffer = new float[1600];
    private float[] silenceBuffer = new float[1600];

    SherpaStreamingSpeechDecoder(SherpaModelRepository.Lease lease) {
        this.lease = lease;
        recognizer = lease.recognizer();
        stream = recognizer.createStream("");
        silentCompanion = recognizer.createStream("");
        decodeBatch = new OnlineStream[] {stream, silentCompanion};
        float[] leftPadding = new float[LEFT_PADDING_SAMPLES];
        stream.acceptWaveform(leftPadding, SAMPLE_RATE);
        silentCompanion.acceptWaveform(leftPadding, SAMPLE_RATE);
        decodeReady();
    }

    @Override
    public Result acceptWaveform(short[] samples, int length) {
        if (length > floatBuffer.length) floatBuffer = new float[length];
        for (int index = 0; index < length; index++) {
            floatBuffer[index] = samples[index] / 32768.0f;
        }
        float[] accepted = length == floatBuffer.length
                ? floatBuffer : Arrays.copyOf(floatBuffer, length);
        if (length > silenceBuffer.length) silenceBuffer = new float[length];
        float[] silence = length == silenceBuffer.length
                ? silenceBuffer : Arrays.copyOf(silenceBuffer, length);
        stream.acceptWaveform(accepted, SAMPLE_RATE);
        silentCompanion.acceptWaveform(silence, SAMPLE_RATE);
        decodeReady();
        return new Result(json("partial", recognizer.getResult(stream)), false);
    }

    @Override
    public String finish() {
        float[] tailPadding = new float[TAIL_PADDING_SAMPLES];
        stream.acceptWaveform(tailPadding, SAMPLE_RATE);
        silentCompanion.acceptWaveform(tailPadding, SAMPLE_RATE);
        stream.inputFinished();
        silentCompanion.inputFinished();
        decodeReady();
        return json("text", recognizer.getResult(stream));
    }

    @Override
    public void close() {
        OnlineStream ownedStream = stream;
        stream = null;
        if (ownedStream != null) ownedStream.release();
        OnlineStream ownedSilentCompanion = silentCompanion;
        silentCompanion = null;
        decodeBatch = null;
        if (ownedSilentCompanion != null) ownedSilentCompanion.release();
        lease.close();
    }

    private void decodeReady() {
        // sherpa-onnx v1.13.4 already has a native multi-stream decoder, but
        // the Kotlin API did not expose it.  The INT8 Zipformer export is
        // stable when its encoder sees a batch dimension > 1; pairing the
        // live stream with silence preserves that behavior without a second
        // recognizer or any user-visible audio.
        while (stream != null && silentCompanion != null
                && recognizer.isReady(stream) && recognizer.isReady(silentCompanion)) {
            recognizer.decodeStreams(decodeBatch);
        }
    }

    private static String json(String textKey, OnlineRecognizerResult result) {
        JSONObject json = new JSONObject();
        try {
            json.put(textKey, result == null || result.getText() == null
                    ? "" : result.getText());
            if (result != null && result.getYsProbs() != null
                    && result.getYsProbs().length > 0) {
                float sum = 0.0f;
                for (float value : result.getYsProbs()) sum += value;
                json.put("mean_log_probability", sum / result.getYsProbs().length);
            }
        } catch (Exception ignored) {
            // JSONObject with fixed keys and primitive values does not normally fail.
        }
        return json.toString();
    }
}
