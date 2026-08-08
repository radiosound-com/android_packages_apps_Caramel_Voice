/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Thread-safe, bounded index shared by context collectors and recognition backends. */
final class RecognitionContextIndex {
    static final int DEFAULT_MAX_HOTWORD_PHRASES = 256;
    static final int MAX_PHRASE_CHARACTERS = 96;

    private final Map<String, List<RecognitionEntity>> entitiesBySource = new HashMap<>();

    synchronized void replaceSource(String sourceId, Collection<RecognitionEntity> entities) {
        String cleanedSource = RecognitionEntity.cleanPhrase(sourceId);
        if (cleanedSource.isEmpty()) throw new IllegalArgumentException("sourceId must not be empty");

        ArrayList<RecognitionEntity> accepted = new ArrayList<>();
        if (entities != null) {
            for (RecognitionEntity entity : entities) {
                if (entity != null && cleanedSource.equals(entity.sourceId)) accepted.add(entity);
            }
        }
        if (accepted.isEmpty()) {
            entitiesBySource.remove(cleanedSource);
        } else {
            entitiesBySource.put(cleanedSource, Collections.unmodifiableList(accepted));
        }
    }

    synchronized Snapshot snapshot() {
        return snapshot(DEFAULT_MAX_HOTWORD_PHRASES);
    }

    synchronized Snapshot snapshot(int maxPhrases) {
        if (maxPhrases < 0) throw new IllegalArgumentException("maxPhrases must not be negative");
        ArrayList<RecognitionEntity> entities = new ArrayList<>();
        for (List<RecognitionEntity> sourceEntities : entitiesBySource.values()) {
            entities.addAll(sourceEntities);
        }
        entities.sort(Comparator
                .comparingInt((RecognitionEntity entity) -> entity.rank).reversed()
                .thenComparing(entity -> normalize(entity.displayText))
                .thenComparing(entity -> entity.stableId));
        return Snapshot.create(entities, maxPhrases);
    }

    static final class Snapshot {
        private final List<RecognitionEntity> entities;
        private final List<String> hotwordPhrases;

        private Snapshot(List<RecognitionEntity> entities, List<String> hotwordPhrases) {
            this.entities = Collections.unmodifiableList(entities);
            this.hotwordPhrases = Collections.unmodifiableList(hotwordPhrases);
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList());
        }

        private static Snapshot create(List<RecognitionEntity> entities, int maxPhrases) {
            if (maxPhrases == 0) {
                return new Snapshot(new ArrayList<>(entities), Collections.emptyList());
            }
            LinkedHashMap<String, String> phrasesByNormalizedText = new LinkedHashMap<>();
            for (RecognitionEntity entity : entities) {
                for (String phrase : entity.phrases) {
                    String cleaned = RecognitionEntity.cleanPhrase(phrase);
                    if (cleaned.isEmpty() || cleaned.length() > MAX_PHRASE_CHARACTERS) continue;
                    String normalized = normalize(cleaned);
                    if (normalized.isEmpty()) continue;
                    phrasesByNormalizedText.putIfAbsent(normalized, cleaned);
                    if (phrasesByNormalizedText.size() == maxPhrases) break;
                }
                if (phrasesByNormalizedText.size() == maxPhrases) break;
            }
            return new Snapshot(
                    new ArrayList<>(entities),
                    new ArrayList<>(phrasesByNormalizedText.values()));
        }

        List<String> hotwordPhrases() {
            return hotwordPhrases;
        }

        String asZipformerHotwords() {
            StringBuilder output = new StringBuilder();
            for (String phrase : hotwordPhrases) {
                String encodedPhrase = normalize(phrase).toUpperCase(Locale.US);
                if (encodedPhrase.isEmpty()) continue;
                if (output.length() > 0) output.append('\n');
                output.append(encodedPhrase);
            }
            return output.toString();
        }

        /**
         * Resolve a whole command argument to authoritative metadata when the ASR text is close.
         * No correction is made if the best candidate is distant or ambiguous.
         */
        String resolve(RecognitionEntity.Domain domain, String hypothesis) {
            String cleanedHypothesis = RecognitionEntity.cleanPhrase(hypothesis);
            String normalizedHypothesis = normalize(cleanedHypothesis);
            if (normalizedHypothesis.isEmpty()) return cleanedHypothesis;

            Match best = null;
            Match second = null;
            for (RecognitionEntity entity : entities) {
                if (entity.domain != domain && entity.domain != RecognitionEntity.Domain.GENERAL) {
                    continue;
                }
                for (String phrase : entity.phrases) {
                    String normalizedPhrase = normalize(phrase);
                    if (normalizedPhrase.isEmpty()) continue;
                    double distance = candidateDistance(normalizedHypothesis, normalizedPhrase);
                    Match candidate = new Match(entity, distance);
                    if (best == null || candidate.compareTo(best) < 0) {
                        second = best;
                        best = candidate;
                    } else if (second == null || candidate.compareTo(second) < 0) {
                        second = candidate;
                    }
                }
            }

            if (best == null) return cleanedHypothesis;
            if (best.distance == 0.0) return best.entity.displayText;
            if (best.distance > 0.42) return cleanedHypothesis;
            if (second != null
                    && second.entity != best.entity
                    && !normalize(second.entity.displayText)
                            .equals(normalize(best.entity.displayText))
                    && second.distance - best.distance < 0.055) {
                return cleanedHypothesis;
            }
            return best.entity.displayText;
        }
    }

    private static final class Match implements Comparable<Match> {
        final RecognitionEntity entity;
        final double distance;

        Match(RecognitionEntity entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }

        @Override
        public int compareTo(Match other) {
            int byDistance = Double.compare(distance, other.distance);
            if (byDistance != 0) return byDistance;
            int byRank = Integer.compare(other.entity.rank, entity.rank);
            if (byRank != 0) return byRank;
            return entity.stableId.compareTo(other.entity.stableId);
        }
    }

    private static double normalizedDistance(String left, String right) {
        String compactLeft = left.replace(" ", "");
        String compactRight = right.replace(" ", "");
        int denominator = Math.max(compactLeft.length(), compactRight.length());
        if (denominator == 0) return 0.0;
        return levenshtein(compactLeft, compactRight) / (double) denominator;
    }

    /**
     * Uses whole-string edit distance first, then a conservative token fallback for streaming
     * ASR's common per-word substitutions. The fallback only applies when two or more tokens
     * independently match (exactly or phonetically), so a shared generic word cannot resolve an
     * unrelated catalog entry by itself.
     */
    private static double candidateDistance(String left, String right) {
        double characterDistance = normalizedDistance(left, right);
        String[] leftTokens = left.split(" ");
        String[] rightTokens = right.split(" ");
        if (leftTokens.length < 2 || rightTokens.length < 2) return characterDistance;

        boolean[] used = new boolean[rightTokens.length];
        double score = 0.0;
        int matchedTokens = 0;
        for (String leftToken : leftTokens) {
            double bestScore = 0.0;
            int bestIndex = -1;
            for (int index = 0; index < rightTokens.length; index++) {
                if (used[index]) continue;
                double tokenScore = tokenSimilarity(leftToken, rightTokens[index]);
                if (tokenScore > bestScore) {
                    bestScore = tokenScore;
                    bestIndex = index;
                }
            }
            if (bestIndex >= 0 && bestScore >= 0.75) {
                used[bestIndex] = true;
                score += bestScore;
                matchedTokens++;
            }
        }
        if (matchedTokens < 2) return characterDistance;
        double tokenDistance = 1.0 - score / Math.max(leftTokens.length, rightTokens.length);
        return Math.min(characterDistance, tokenDistance);
    }

    private static double tokenSimilarity(String left, String right) {
        if (left.equals(right)) return 1.0;
        String leftSoundex = soundex(left);
        String rightSoundex = soundex(right);
        if (left.length() >= 3 && right.length() >= 3
                && !leftSoundex.isEmpty() && leftSoundex.equals(rightSoundex)) {
            return 0.90;
        }
        double distance = normalizedDistance(left, right);
        return distance <= 0.25 ? 1.0 - distance : 0.0;
    }

    /** Small English-oriented phonetic key used only as a resolver fallback. */
    private static String soundex(String value) {
        if (value == null || value.isEmpty()) return "";
        String upper = value.toUpperCase(Locale.US);
        char first = upper.charAt(0);
        if (first < 'A' || first > 'Z') return "";
        StringBuilder output = new StringBuilder(4).append(first);
        char previousCode = soundexCode(first);
        for (int index = 1; index < upper.length() && output.length() < 4; index++) {
            char code = soundexCode(upper.charAt(index));
            if (code != '0' && code != previousCode) output.append(code);
            previousCode = code;
        }
        while (output.length() < 4) output.append('0');
        return output.toString();
    }

    private static char soundexCode(char value) {
        if ("BFPV".indexOf(value) >= 0) return '1';
        if ("CGJKQSXZ".indexOf(value) >= 0) return '2';
        if ("DT".indexOf(value) >= 0) return '3';
        if (value == 'L') return '4';
        if ("MN".indexOf(value) >= 0) return '5';
        if (value == 'R') return '6';
        return '0';
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    static String normalize(String value) {
        String decomposed = Normalizer.normalize(
                RecognitionEntity.cleanPhrase(value), Normalizer.Form.NFKD);
        return decomposed
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
