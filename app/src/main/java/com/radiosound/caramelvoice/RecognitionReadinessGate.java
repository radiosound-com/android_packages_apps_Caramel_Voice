/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.util.ArrayList;
import java.util.List;

/** Delivers model-readiness results without invoking callbacks while holding its lock. */
final class RecognitionReadinessGate {
    interface Callback {
        void onReady(boolean available);
    }

    private final ArrayList<Callback> pending = new ArrayList<>();
    private boolean complete;
    private boolean available;

    void await(Callback callback) {
        if (callback == null) return;

        boolean dispatch;
        boolean result;
        synchronized (this) {
            if (!complete) {
                pending.add(callback);
                return;
            }
            dispatch = true;
            result = available;
        }
        if (dispatch) callback.onReady(result);
    }

    void complete(boolean available) {
        List<Callback> callbacks;
        synchronized (this) {
            complete = true;
            this.available = available;
            callbacks = new ArrayList<>(pending);
            pending.clear();
        }
        for (Callback callback : callbacks) callback.onReady(available);
    }

    synchronized void reset() {
        complete = false;
        available = false;
    }

    synchronized boolean isComplete() {
        return complete;
    }

    synchronized boolean isAvailable() {
        return complete && available;
    }
}
