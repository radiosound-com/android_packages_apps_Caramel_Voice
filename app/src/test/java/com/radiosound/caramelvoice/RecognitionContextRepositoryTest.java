/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class RecognitionContextRepositoryTest {
    @Test
    public void doesNotDuplicateArtistAlreadyContainedInTitle() {
        List<RecognitionEntity> entities = new ArrayList<>();

        RecognitionContextRepository.addMediaEntity(
                entities,
                "media-store:1",
                "media-store",
                "Eric Prydz Opus",
                "Eric Prydz",
                "",
                100);

        assertEquals(1, entities.size());
        assertEquals("Eric Prydz Opus", entities.get(0).displayText);
    }

    @Test
    public void prefixesArtistWhenTitleDoesNotContainIt() {
        List<RecognitionEntity> entities = new ArrayList<>();

        RecognitionContextRepository.addMediaEntity(
                entities,
                "media-store:2",
                "media-store",
                "Opus",
                "Eric Prydz",
                "",
                100);

        assertEquals("Eric Prydz Opus", entities.get(0).displayText);
    }
}
