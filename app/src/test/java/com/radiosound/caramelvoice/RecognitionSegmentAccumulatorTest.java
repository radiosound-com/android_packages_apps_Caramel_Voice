package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RecognitionSegmentAccumulatorTest {
    @Test
    public void preservesCommandPrefixAcrossVoskFinalizedSegments() {
        RecognitionSegmentAccumulator accumulator = new RecognitionSegmentAccumulator(5);

        accumulator.acceptFinalizedSegment(Collections.singletonList("play"));
        accumulator.acceptPartialSegment(Collections.singletonList("erik prince"));
        List<String> alternatives = accumulator.finish(Arrays.asList(
                "erik prince opens",
                "like erik prince opens",
                "erik prince opus",
                "like eric prince opens",
                "eric prince opens"));

        assertTrue(alternatives.contains("play erik prince opus"));
        VoiceCommandRouter.Command command = VoiceCommandRouter.routeBest(alternatives);
        assertEquals(VoiceCommandRouter.Type.PLAY, command.type);
    }

    @Test
    public void fallsBackToLatestPartialWhenFinalSegmentIsEmpty() {
        RecognitionSegmentAccumulator accumulator = new RecognitionSegmentAccumulator(5);
        accumulator.acceptFinalizedSegment(Collections.singletonList("navigate to"));
        accumulator.acceptPartialSegment(Collections.singletonList("times square"));

        assertEquals(
                Collections.singletonList("navigate to times square"),
                accumulator.finish(Collections.emptyList()));
    }

    @Test
    public void combinesRankedAlternativesWithoutExceedingLimit() {
        RecognitionSegmentAccumulator accumulator = new RecognitionSegmentAccumulator(3);
        accumulator.acceptFinalizedSegment(Arrays.asList("play", "plate"));

        assertEquals(
                Arrays.asList("play opus", "play office", "plate opus"),
                accumulator.finish(Arrays.asList("opus", "office", "opens")));
    }
}
