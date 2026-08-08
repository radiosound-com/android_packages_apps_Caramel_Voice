/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.Context;

/** Selects the product backend's readiness signal before opening the microphone. */
final class RecognitionModelReadiness {
    private RecognitionModelReadiness() {}

    static void whenReady(Context context, RecognitionReadinessGate.Callback callback) {
        if (RecognitionBackendProfile.load(context).engine
                == RecognitionBackendProfile.Engine.ZIPFORMER) {
            SherpaModelRepository.whenReady(context, callback);
        } else {
            VoskModelRepository.whenReady(context, callback);
        }
    }
}
