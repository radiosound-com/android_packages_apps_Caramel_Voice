/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class VoiceResponseCoordinatorTest {
    @Test
    public void externalActionWaitsForSpeechCompletion() {
        List<String> events = new ArrayList<>();
        CallbackSpeaker speaker = new CallbackSpeaker(events);

        VoiceResponseCoordinator.respond(
                "Opening home in OsmAnd.", speaker, () -> events.add("launch"));

        assertEquals(List.of("speak:Opening home in OsmAnd."), events);
        assertNotNull(speaker.completion);
        assertFalse(events.contains("launch"));

        speaker.completion.run();

        assertEquals(List.of("speak:Opening home in OsmAnd.", "launch"), events);
    }

    @Test
    public void externalActionGetsGenerationGracePeriod() {
        assertEquals(20_000L, VoiceResponseCoordinator.timeoutMs(null));
        assertEquals(20_000L, VoiceResponseCoordinator.timeoutMs(() -> {}));
    }

    private static final class CallbackSpeaker implements VoiceResponseCoordinator.Speaker {
        private final List<String> events;
        Runnable completion;

        CallbackSpeaker(List<String> events) {
            this.events = events;
        }

        @Override
        public void speak(String text, Runnable completion) {
            events.add("speak:" + text);
            this.completion = completion;
        }
    }
}
