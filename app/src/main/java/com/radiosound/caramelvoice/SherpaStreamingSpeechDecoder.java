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
    private float[] floatBuffer = new float[1600];

    SherpaStreamingSpeechDecoder(SherpaModelRepository.Lease lease) {
        this.lease = lease;
        recognizer = lease.recognizer();
        stream = recognizer.createStream("");
        stream.acceptWaveform(new float[LEFT_PADDING_SAMPLES], SAMPLE_RATE);
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
        stream.acceptWaveform(accepted, SAMPLE_RATE);
        decodeReady();
        return new Result(json("partial", recognizer.getResult(stream)), false);
    }

    @Override
    public String finish() {
        stream.acceptWaveform(new float[TAIL_PADDING_SAMPLES], SAMPLE_RATE);
        stream.inputFinished();
        decodeReady();
        return json("text", recognizer.getResult(stream));
    }

    @Override
    public void close() {
        OnlineStream ownedStream = stream;
        stream = null;
        if (ownedStream != null) ownedStream.release();
        lease.close();
    }

    private void decodeReady() {
        while (recognizer.isReady(stream)) recognizer.decode(stream);
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
