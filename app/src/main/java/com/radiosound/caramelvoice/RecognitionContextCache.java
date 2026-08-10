/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Small, versioned serializer for the local recognition context cache. */
final class RecognitionContextCache {
    static final String HEADER = "caramel-context-cache-v1";
    static final long MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTITIES = 1200;
    private static final String FIELD_SEPARATOR = "\u001f";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private RecognitionContextCache() {}

    static void write(Writer output, Collection<RecognitionEntity> entities, long nowMillis)
            throws IOException {
        output.write(HEADER);
        output.write('\t');
        output.write(Long.toString(nowMillis));
        output.write('\n');

        int written = 0;
        for (RecognitionEntity entity : entities) {
            if (entity == null || written == MAX_ENTITIES) break;
            output.write(encode(entity.sourceId));
            output.write('\t');
            output.write(encode(entity.stableId));
            output.write('\t');
            output.write(entity.domain.name());
            output.write('\t');
            output.write(Integer.toString(entity.rank));
            output.write('\t');
            output.write(encode(entity.displayText));
            output.write('\t');
            output.write(encode(String.join(FIELD_SEPARATOR, entity.phrases)));
            output.write('\n');
            written++;
        }
    }

    static List<RecognitionEntity> read(BufferedReader input, long nowMillis) throws IOException {
        String header = input.readLine();
        if (header == null) return Collections.emptyList();
        String[] headerFields = header.split("\\t", -1);
        if (headerFields.length != 2 || !HEADER.equals(headerFields[0])) {
            return Collections.emptyList();
        }

        long writtenAt;
        try {
            writtenAt = Long.parseLong(headerFields[1]);
        } catch (NumberFormatException exception) {
            return Collections.emptyList();
        }
        if (writtenAt > nowMillis || nowMillis - writtenAt > MAX_AGE_MILLIS) {
            return Collections.emptyList();
        }

        ArrayList<RecognitionEntity> entities = new ArrayList<>();
        String line;
        while ((line = input.readLine()) != null && entities.size() < MAX_ENTITIES) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 6) continue;
            try {
                String sourceId = decode(fields[0]);
                String stableId = decode(fields[1]);
                RecognitionEntity.Domain domain = RecognitionEntity.Domain.valueOf(fields[2]);
                int rank = Integer.parseInt(fields[3]);
                String displayText = decode(fields[4]);
                String encodedPhrases = decode(fields[5]);
                ArrayList<String> aliases = new ArrayList<>();
                if (!encodedPhrases.isEmpty()) {
                    String[] phrases = encodedPhrases.split(FIELD_SEPARATOR, -1);
                    for (String phrase : phrases) {
                        if (!phrase.equals(displayText)) aliases.add(phrase);
                    }
                }
                entities.add(new RecognitionEntity(
                        stableId, sourceId, domain, displayText, aliases, rank));
            } catch (IllegalArgumentException exception) {
                // Ignore only the damaged record; a single bad catalog row must not discard
                // the rest of the cached context.
            }
        }
        return entities;
    }

    private static String encode(String value) {
        return ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(DECODER.decode(value), StandardCharsets.UTF_8);
    }
}
