/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

/** Small backend-neutral seam used by the shared AudioRecord/VAD pipeline. */
interface StreamingSpeechDecoder extends AutoCloseable {
    final class Result {
        final String hypothesis;
        final boolean segmentComplete;

        Result(String hypothesis, boolean segmentComplete) {
            this.hypothesis = hypothesis == null ? "" : hypothesis;
            this.segmentComplete = segmentComplete;
        }
    }

    Result acceptWaveform(short[] samples, int length) throws Exception;

    String finish() throws Exception;

    @Override
    default void close() throws Exception {}
}
