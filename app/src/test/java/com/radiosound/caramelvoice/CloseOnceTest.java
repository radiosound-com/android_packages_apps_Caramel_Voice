/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CloseOnceTest {
    @Test
    public void timeoutAndLateResultCanOnlyCloseNativeResourceOnce() throws Exception {
        CountingResource resource = new CountingResource();
        CloseOnce<CountingResource> owner = new CloseOnce<>(resource);

        assertTrue(owner.close());
        assertFalse(owner.close());
        assertEquals(1, resource.closeCount);
    }

    private static final class CountingResource implements AutoCloseable {
        int closeCount;

        @Override public void close() {
            closeCount++;
        }
    }
}
