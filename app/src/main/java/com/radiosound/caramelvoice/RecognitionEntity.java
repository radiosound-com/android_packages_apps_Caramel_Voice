/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** One app-neutral entity that can improve recognition and command resolution. */
final class RecognitionEntity {
    enum Domain {
        GENERAL,
        MEDIA,
        NAVIGATION,
        CONTACT,
        APP
    }

    final String stableId;
    final String sourceId;
    final Domain domain;
    final String displayText;
    final List<String> phrases;
    final int rank;

    RecognitionEntity(
            String stableId,
            String sourceId,
            Domain domain,
            String displayText,
            List<String> aliases,
            int rank) {
        this.stableId = requireText(stableId, "stableId");
        this.sourceId = requireText(sourceId, "sourceId");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.displayText = requireText(displayText, "displayText");
        this.rank = rank;

        LinkedHashSet<String> uniquePhrases = new LinkedHashSet<>();
        uniquePhrases.add(this.displayText);
        if (aliases != null) {
            for (String alias : aliases) {
                String cleaned = cleanPhrase(alias);
                if (!cleaned.isEmpty()) uniquePhrases.add(cleaned);
            }
        }
        phrases = Collections.unmodifiableList(new ArrayList<>(uniquePhrases));
    }

    RecognitionEntity(
            String stableId,
            String sourceId,
            Domain domain,
            String displayText,
            int rank) {
        this(stableId, sourceId, domain, displayText, Collections.emptyList(), rank);
    }

    private static String requireText(String value, String name) {
        String cleaned = cleanPhrase(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return cleaned;
    }

    static String cleanPhrase(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }
}
