/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import org.vosk.Recognizer;

/** Adapts Vosk to the backend-neutral microphone pipeline. */
final class VoskStreamingSpeechDecoder implements StreamingSpeechDecoder {
    private final Recognizer recognizer;

    VoskStreamingSpeechDecoder(Recognizer recognizer) {
        this.recognizer = recognizer;
    }

    @Override
    public Result acceptWaveform(short[] samples, int length) {
        boolean complete = recognizer.acceptWaveForm(samples, length);
        return new Result(
                complete ? recognizer.getResult() : recognizer.getPartialResult(),
                complete);
    }

    @Override
    public String finish() {
        return recognizer.getFinalResult();
    }
}
