/*
 * Copyright 2026 Radio Sound, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.radiosound.caramelvoice;

import java.util.ArrayList;
import java.util.List;

/** Preserves ranked hypotheses when Vosk finalizes one utterance in multiple segments. */
final class RecognitionSegmentAccumulator {
    private final int maximumAlternatives;
    private ArrayList<String> completedAlternatives = new ArrayList<>();
    private ArrayList<String> latestPartialAlternatives = new ArrayList<>();

    RecognitionSegmentAccumulator(int maximumAlternatives) {
        if (maximumAlternatives <= 0) {
            throw new IllegalArgumentException("maximumAlternatives must be positive");
        }
        this.maximumAlternatives = maximumAlternatives;
    }

    ArrayList<String> acceptPartialSegment(List<String> alternatives) {
        latestPartialAlternatives = clean(alternatives);
        return combine(completedAlternatives, latestPartialAlternatives);
    }

    ArrayList<String> acceptFinalizedSegment(List<String> alternatives) {
        ArrayList<String> segment = clean(alternatives);
        if (!segment.isEmpty()) completedAlternatives = combine(completedAlternatives, segment);
        latestPartialAlternatives.clear();
        return new ArrayList<>(completedAlternatives);
    }

    ArrayList<String> finish(List<String> finalAlternatives) {
        ArrayList<String> tail = clean(finalAlternatives);
        if (tail.isEmpty()) tail = latestPartialAlternatives;
        return combine(completedAlternatives, tail);
    }

    private ArrayList<String> combine(List<String> prefixes, List<String> suffixes) {
        if (prefixes.isEmpty()) return limitedCopy(suffixes);
        if (suffixes.isEmpty()) return limitedCopy(prefixes);

        ArrayList<String> combined = new ArrayList<>();
        int maximumRank = prefixes.size() + suffixes.size() - 2;
        for (int rank = 0; rank <= maximumRank && combined.size() < maximumAlternatives; rank++) {
            for (int prefixIndex = 0;
                    prefixIndex < prefixes.size() && combined.size() < maximumAlternatives;
                    prefixIndex++) {
                int suffixIndex = rank - prefixIndex;
                if (suffixIndex < 0 || suffixIndex >= suffixes.size()) continue;
                addUnique(combined,
                        (prefixes.get(prefixIndex) + " " + suffixes.get(suffixIndex)).trim());
            }
        }
        return combined;
    }

    private ArrayList<String> clean(List<String> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) return new ArrayList<>();
        ArrayList<String> cleaned = new ArrayList<>();
        for (String alternative : alternatives) {
            String text = alternative == null
                    ? ""
                    : alternative.trim().replaceAll("\\s+", " ");
            if (!text.isEmpty()) addUnique(cleaned, text);
            if (cleaned.size() >= maximumAlternatives) break;
        }
        return cleaned;
    }

    private ArrayList<String> limitedCopy(List<String> alternatives) {
        if (alternatives.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(alternatives.subList(
                0, Math.min(alternatives.size(), maximumAlternatives)));
    }

    private static void addUnique(ArrayList<String> values, String value) {
        if (!value.isEmpty() && !values.contains(value)) values.add(value);
    }
}
