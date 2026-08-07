/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Properties;

import org.junit.Test;

public final class VoskModelProfileTest {
    @Test
    public void missingModelDefaultsToCompactApkAsset() {
        VoskModelProfile profile = VoskModelProfile.fromProperties(new Properties());

        assertEquals(VoskModelProfile.SMALL_MODEL, profile.modelDirectory);
        assertSame(VoskModelProfile.Source.APK_ASSET, profile.source);
    }

    @Test
    public void lgraphSelectsProductArchive() {
        Properties properties = new Properties();
        properties.setProperty("model", "lgraph");

        VoskModelProfile profile = VoskModelProfile.fromProperties(properties);

        assertEquals(VoskModelProfile.LGRAPH_MODEL, profile.modelDirectory);
        assertSame(VoskModelProfile.Source.PRODUCT_ARCHIVE, profile.source);
        assertEquals(VoskModelProfile.LGRAPH_ARCHIVE, profile.productArchive);
    }

    @Test
    public void unknownModelFallsBackToCompact() {
        Properties properties = new Properties();
        properties.setProperty("model", "whisper-medium");

        VoskModelProfile profile = VoskModelProfile.fromProperties(properties);

        assertEquals(VoskModelProfile.SMALL_MODEL, profile.modelDirectory);
        assertSame(VoskModelProfile.Source.APK_ASSET, profile.source);
    }
}
