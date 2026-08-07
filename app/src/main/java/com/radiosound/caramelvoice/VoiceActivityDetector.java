/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

/** Lightweight adaptive energy detector for 100 ms, 16-bit PCM microphone frames. */
final class VoiceActivityDetector {
    static final int TRAILING_SILENCE_CHUNKS = 12;
    static final int NO_SPEECH_TIMEOUT_CHUNKS = 100;

    private static final int CALIBRATION_CHUNKS = 5;
    private static final int SPEECH_START_CHUNKS = 2;
    private static final int MAX_SPEECH_CHUNKS = 100;
    private static final double ABSOLUTE_MINIMUM_THRESHOLD_DBFS = -52.0;
    private static final double ABSOLUTE_MAXIMUM_THRESHOLD_DBFS = -32.0;
    private static final double IMMEDIATE_SPEECH_THRESHOLD_DBFS = -38.0;
    private static final double NOISE_MARGIN_DB = 12.0;
    private static final double MINIMUM_LEVEL_DBFS = -120.0;
    private static final double NOISE_SMOOTHING = 0.9;

    enum Event {
        QUIET,
        SPEECH_STARTED,
        SPEECH,
        END_OF_SPEECH,
        NO_SPEECH_TIMEOUT
    }

    private int totalChunks;
    private int consecutiveSpeechChunks;
    private int trailingSilenceChunks;
    private int speechChunks;
    private int noiseSamples;
    private boolean speechStarted;
    private double noiseFloorDbfs = -70.0;
    private double lastLevelDbfs = MINIMUM_LEVEL_DBFS;
    private double thresholdDbfs = ABSOLUTE_MINIMUM_THRESHOLD_DBFS;

    Event accept(short[] samples, int count) {
        lastLevelDbfs = levelDbfs(samples, count);
        totalChunks++;
        thresholdDbfs = clamp(
                noiseFloorDbfs + NOISE_MARGIN_DB,
                ABSOLUTE_MINIMUM_THRESHOLD_DBFS,
                ABSOLUTE_MAXIMUM_THRESHOLD_DBFS);

        if (!speechStarted) {
            boolean calibrating = totalChunks <= CALIBRATION_CHUNKS;
            boolean voiced = calibrating
                    ? lastLevelDbfs >= IMMEDIATE_SPEECH_THRESHOLD_DBFS
                    : lastLevelDbfs >= thresholdDbfs;
            if (voiced) {
                consecutiveSpeechChunks++;
                if (consecutiveSpeechChunks >= SPEECH_START_CHUNKS) {
                    speechStarted = true;
                    speechChunks = consecutiveSpeechChunks;
                    trailingSilenceChunks = 0;
                    return Event.SPEECH_STARTED;
                }
            } else {
                consecutiveSpeechChunks = 0;
                updateNoiseFloor(lastLevelDbfs);
            }

            return totalChunks >= NO_SPEECH_TIMEOUT_CHUNKS
                    ? Event.NO_SPEECH_TIMEOUT
                    : Event.QUIET;
        }

        speechChunks++;
        if (speechChunks >= MAX_SPEECH_CHUNKS) return Event.END_OF_SPEECH;
        if (lastLevelDbfs >= thresholdDbfs) {
            trailingSilenceChunks = 0;
            return Event.SPEECH;
        }
        trailingSilenceChunks++;
        return trailingSilenceChunks >= TRAILING_SILENCE_CHUNKS
                ? Event.END_OF_SPEECH
                : Event.QUIET;
    }

    double getLastLevelDbfs() {
        return lastLevelDbfs;
    }

    double getThresholdDbfs() {
        return thresholdDbfs;
    }

    private void updateNoiseFloor(double levelDbfs) {
        if (noiseSamples == 0) {
            noiseFloorDbfs = levelDbfs;
        } else {
            noiseFloorDbfs = NOISE_SMOOTHING * noiseFloorDbfs
                    + (1.0 - NOISE_SMOOTHING) * levelDbfs;
        }
        noiseSamples++;
    }

    private static double levelDbfs(short[] samples, int count) {
        if (samples == null || count <= 0) return MINIMUM_LEVEL_DBFS;
        int safeCount = Math.min(count, samples.length);
        double sumSquares = 0.0;
        for (int index = 0; index < safeCount; index++) {
            double sample = samples[index];
            sumSquares += sample * sample;
        }
        if (sumSquares == 0.0) return MINIMUM_LEVEL_DBFS;
        double rms = Math.sqrt(sumSquares / safeCount);
        return 20.0 * Math.log10(rms / Short.MAX_VALUE);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
