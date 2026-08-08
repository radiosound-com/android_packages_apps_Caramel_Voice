/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.Test;

public final class AppSearchContextCollectorTest {
    @Test
    public void classifiesStandardSemanticSchemasWithoutPackageNames() {
        assertEquals(
                RecognitionEntity.Domain.NAVIGATION,
                AppSearchContextCollector.domainFor("builtin:Place", new HashSet<>()));
        assertEquals(
                RecognitionEntity.Domain.MEDIA,
                AppSearchContextCollector.domainFor("MusicRecording", new HashSet<>()));
        assertEquals(
                RecognitionEntity.Domain.CONTACT,
                AppSearchContextCollector.domainFor("builtin:Person", new HashSet<>()));
    }

    @Test
    public void recognizesNavigationPropertiesOnCustomSchemas() {
        assertEquals(
                RecognitionEntity.Domain.NAVIGATION,
                AppSearchContextCollector.domainFor(
                        "Favorite", new HashSet<>(Arrays.asList("name", "address"))));
    }

    @Test
    public void ignoresUnrelatedPrivateDocumentShapes() {
        assertNull(AppSearchContextCollector.domainFor(
                "Note", new HashSet<>(Arrays.asList("title", "body"))));
    }
}
