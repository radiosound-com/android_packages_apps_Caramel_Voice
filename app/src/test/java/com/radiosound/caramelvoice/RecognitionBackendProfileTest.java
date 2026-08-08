/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;

import java.util.Properties;

import org.junit.Test;

public final class RecognitionBackendProfileTest {
    @Test
    public void absentEngineKeepsVoskFallback() {
        RecognitionBackendProfile profile = RecognitionBackendProfile.fromProperties(
                new Properties());

        assertEquals(RecognitionBackendProfile.Engine.VOSK, profile.engine);
    }

    @Test
    public void zipformerProfileLoadsBoundedPiTuning() {
        Properties properties = new Properties();
        properties.setProperty("engine", "zipformer");
        properties.setProperty("threads", "99");
        properties.setProperty("max_active_paths", "4");
        properties.setProperty("hotwords_score", "4.0");

        RecognitionBackendProfile profile = RecognitionBackendProfile.fromProperties(properties);

        assertEquals(RecognitionBackendProfile.Engine.ZIPFORMER, profile.engine);
        assertEquals(8, profile.threads);
        assertEquals(4, profile.maxActivePaths);
        assertEquals(4.0f, profile.hotwordsScore, 0.0f);
    }
}
