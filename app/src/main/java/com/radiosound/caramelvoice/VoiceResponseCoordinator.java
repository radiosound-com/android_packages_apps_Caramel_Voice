/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

/** Keeps externally visible command actions behind their spoken response. */
final class VoiceResponseCoordinator {
    private static final long DEFAULT_TIMEOUT_MS = 8_000L;
    private static final long ACTION_TIMEOUT_MS = 20_000L;

    interface Speaker {
        void speak(String text, Runnable onComplete);
    }

    private VoiceResponseCoordinator() {}

    static void respond(String text, Speaker speaker, Runnable afterSpeech) {
        if (speaker == null) {
            if (afterSpeech != null) afterSpeech.run();
            return;
        }
        speaker.speak(text, afterSpeech);
    }

    static long timeoutMs(Runnable afterSpeech) {
        return afterSpeech == null ? DEFAULT_TIMEOUT_MS : ACTION_TIMEOUT_MS;
    }
}
