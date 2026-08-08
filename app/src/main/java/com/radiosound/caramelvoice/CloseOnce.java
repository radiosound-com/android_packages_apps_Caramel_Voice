/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

/** Owns a native-backed resource and guarantees that it is closed at most once. */
final class CloseOnce<T extends AutoCloseable> {
    private T resource;

    CloseOnce(T resource) {
        if (resource == null) throw new NullPointerException("resource");
        this.resource = resource;
    }

    synchronized T get() {
        return resource;
    }

    synchronized boolean close() throws Exception {
        T owned = resource;
        if (owned == null) return false;
        resource = null;
        owned.close();
        return true;
    }
}
