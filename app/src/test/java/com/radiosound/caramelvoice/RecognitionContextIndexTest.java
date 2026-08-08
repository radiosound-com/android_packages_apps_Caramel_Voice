/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class RecognitionContextIndexTest {
    @Test
    public void boundsAndRanksHotwordsAcrossGenericSources() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("media:one", Arrays.asList(
                entity("track-a", "media:one", "Low-ranked track", 10),
                entity("track-b", "media:one", "High-ranked track", 90)));
        index.replaceSource("media:two", Collections.singletonList(
                entity("track-c", "media:two", "High-ranked track", 80)));

        assertEquals(
                Arrays.asList("High-ranked track", "Low-ranked track"),
                index.snapshot(2).hotwordPhrases());
        assertEquals("HIGH RANKED TRACK\nLOW RANKED TRACK", index.snapshot(2).asZipformerHotwords());
    }

    @Test
    public void stripsZipformerBoostSyntaxFromCatalogPunctuation() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("media:one", Collections.singletonList(
                entity("playlist", "media:one", "Guild Wars 2: Music to Game By", 10)));

        assertEquals(
                "GUILD WARS 2 MUSIC TO GAME BY",
                index.snapshot().asZipformerHotwords());
    }

    @Test
    public void replacingSourceRemovesStaleCatalogEntries() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("player:example", Collections.singletonList(
                entity("old", "player:example", "Old title", 50)));
        index.replaceSource("player:example", Collections.singletonList(
                entity("new", "player:example", "New title", 50)));

        assertEquals(Collections.singletonList("New title"), index.snapshot().hotwordPhrases());
    }

    @Test
    public void zeroPhraseSnapshotIsEmpty() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("media:one", Collections.singletonList(
                entity("track", "media:one", "A track", 10)));

        assertEquals(Collections.emptyList(), index.snapshot(0).hotwordPhrases());
    }

    @Test
    public void resolvesPhoneticAsrSpellingFromArbitraryMetadata() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("player:example", Collections.singletonList(new RecognitionEntity(
                "catalog:42",
                "player:example",
                RecognitionEntity.Domain.MEDIA,
                "Eric Prydz Opus",
                Arrays.asList("Eric Prydz", "Opus"),
                100)));

        assertEquals(
                "Eric Prydz Opus",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "eric pride's opus"));
        assertEquals(
                "Eric Prydz Opus",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "eric prade's opus"));
        assertEquals(
                "Eric Prydz Opus",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "eric prid's opus"));
    }

    @Test
    public void resolvesMultipleNoisyTokensWithoutCatalogSpecificKnowledge() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("media-store", Collections.singletonList(new RecognitionEntity(
                "catalog:43",
                "media-store",
                RecognitionEntity.Domain.MEDIA,
                "Eric Prydz Opus",
                Collections.emptyList(),
                100)));

        assertEquals(
                "Eric Prydz Opus",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "arid prides opus"));
    }

    @Test
    public void duplicateSourcesReinforceTheSameResolvedEntity() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("active-session", Collections.singletonList(new RecognitionEntity(
                "active:42",
                "active-session",
                RecognitionEntity.Domain.MEDIA,
                "Eric Prydz Opus",
                Arrays.asList("Opus", "Eric Prydz"),
                100)));
        index.replaceSource("media-browser", Collections.singletonList(new RecognitionEntity(
                "browser:42",
                "media-browser",
                RecognitionEntity.Domain.MEDIA,
                "Eric Prydz Opus",
                Arrays.asList("Opus", "Eric Prydz"),
                90)));

        assertEquals(
                "Eric Prydz Opus",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "eric pry's opus"));
    }

    @Test
    public void doesNotReplaceDistantOrWrongDomainText() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("navigation:history", Collections.singletonList(new RecognitionEntity(
                "place:1",
                "navigation:history",
                RecognitionEntity.Domain.NAVIGATION,
                "Museum of Modern Art",
                100)));

        assertEquals(
                "central station",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.NAVIGATION, "central station"));
        assertEquals(
                "museum of modern art",
                index.snapshot().resolve(
                        RecognitionEntity.Domain.MEDIA, "museum of modern art"));
    }

    @Test
    public void leavesAmbiguousNearMatchesUnchanged() {
        RecognitionContextIndex index = new RecognitionContextIndex();
        index.replaceSource("contacts", Arrays.asList(
                new RecognitionEntity("1", "contacts", RecognitionEntity.Domain.CONTACT,
                        "Jon Smith", 100),
                new RecognitionEntity("2", "contacts", RecognitionEntity.Domain.CONTACT,
                        "John Smyth", 100)));

        assertEquals(
                "john smith",
                index.snapshot().resolve(RecognitionEntity.Domain.CONTACT, "john smith"));
    }

    private static RecognitionEntity entity(
            String id, String source, String text, int rank) {
        return new RecognitionEntity(id, source, RecognitionEntity.Domain.MEDIA, text, rank);
    }
}
