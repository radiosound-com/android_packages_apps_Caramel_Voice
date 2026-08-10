/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public final class RecognitionContextCacheTest {
    @Test
    public void roundTripPreservesEntitiesAndAliases() throws Exception {
        RecognitionEntity entity = new RecognitionEntity(
                "media:1", "media-browser", RecognitionEntity.Domain.MEDIA,
                "Eric Prydz Opus", Arrays.asList("Opus", "Eric Prydz"), 700);
        StringWriter serialized = new StringWriter();
        RecognitionContextCache.write(serialized, Arrays.asList(entity), 1000L);

        List<RecognitionEntity> restored = RecognitionContextCache.read(
                new BufferedReader(new StringReader(serialized.toString())), 1000L + 1L);
        assertEquals(1, restored.size());
        assertEquals(entity.stableId, restored.get(0).stableId);
        assertEquals(entity.sourceId, restored.get(0).sourceId);
        assertEquals(entity.domain, restored.get(0).domain);
        assertEquals(entity.displayText, restored.get(0).displayText);
        assertEquals(entity.phrases, restored.get(0).phrases);
    }

    @Test
    public void staleCacheIsIgnored() throws Exception {
        StringWriter serialized = new StringWriter();
        RecognitionContextCache.write(serialized, Arrays.asList(new RecognitionEntity(
                "nav:1", "appsearch", RecognitionEntity.Domain.NAVIGATION,
                "Home", 850)), 0L);

        List<RecognitionEntity> restored = RecognitionContextCache.read(
                new BufferedReader(new StringReader(serialized.toString())),
                RecognitionContextCache.MAX_AGE_MILLIS + 1L);
        assertTrue(restored.isEmpty());
    }
}
