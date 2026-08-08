/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class RecognitionReadinessGateTest {
    @Test
    public void queuedCallbacksWaitForModelAvailability() {
        RecognitionReadinessGate gate = new RecognitionReadinessGate();
        List<Boolean> results = new ArrayList<>();

        gate.await(results::add);

        assertTrue(results.isEmpty());
        gate.complete(true);
        assertEquals(Arrays.asList(true), results);
    }

    @Test
    public void lateCallbacksReceiveTheCurrentResultImmediately() {
        RecognitionReadinessGate gate = new RecognitionReadinessGate();
        gate.complete(true);
        List<Boolean> results = new ArrayList<>();

        gate.await(results::add);

        assertEquals(Arrays.asList(true), results);
    }

    @Test
    public void failedLoadCanBeResetForRetry() {
        RecognitionReadinessGate gate = new RecognitionReadinessGate();
        List<Boolean> results = new ArrayList<>();
        gate.complete(false);
        gate.reset();

        gate.await(results::add);
        assertTrue(results.isEmpty());

        gate.complete(true);
        assertEquals(Arrays.asList(true), results);
        assertTrue(gate.isComplete());
        assertTrue(gate.isAvailable());
    }

    @Test
    public void failedResultIsObservable() {
        RecognitionReadinessGate gate = new RecognitionReadinessGate();
        gate.complete(false);

        assertTrue(gate.isComplete());
        assertFalse(gate.isAvailable());
    }
}
