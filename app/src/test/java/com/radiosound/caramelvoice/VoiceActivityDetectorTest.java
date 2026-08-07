package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VoiceActivityDetectorTest {
    private static final int FRAME_SAMPLES = 1600;

    @Test
    public void startsAfterTwoVoicedFramesFollowingCalibration() {
        VoiceActivityDetector detector = new VoiceActivityDetector();
        feed(detector, 5, 8);

        assertEquals(VoiceActivityDetector.Event.QUIET, detector.accept(frame(2500), FRAME_SAMPLES));
        assertEquals(
                VoiceActivityDetector.Event.SPEECH_STARTED,
                detector.accept(frame(2500), FRAME_SAMPLES));
    }

    @Test
    public void ignoresShortPauseAndEndsAfterTrailingSilence() {
        VoiceActivityDetector detector = startedDetector();

        feed(detector, VoiceActivityDetector.TRAILING_SILENCE_CHUNKS - 1, 8);
        assertEquals(VoiceActivityDetector.Event.SPEECH, detector.accept(frame(2500), FRAME_SAMPLES));
        feed(detector, VoiceActivityDetector.TRAILING_SILENCE_CHUNKS - 1, 8);
        assertEquals(
                VoiceActivityDetector.Event.END_OF_SPEECH,
                detector.accept(frame(8), FRAME_SAMPLES));
    }

    @Test
    public void timesOutWhenNoSpeechArrives() {
        VoiceActivityDetector detector = new VoiceActivityDetector();

        feed(detector, VoiceActivityDetector.NO_SPEECH_TIMEOUT_CHUNKS - 1, 8);
        assertEquals(
                VoiceActivityDetector.Event.NO_SPEECH_TIMEOUT,
                detector.accept(frame(8), FRAME_SAMPLES));
    }

    @Test
    public void adaptsToAConstantNoisyRoom() {
        VoiceActivityDetector detector = new VoiceActivityDetector();
        feed(detector, 8, 300);

        assertEquals(VoiceActivityDetector.Event.QUIET, detector.accept(frame(300), FRAME_SAMPLES));
        assertEquals(VoiceActivityDetector.Event.QUIET, detector.accept(frame(2500), FRAME_SAMPLES));
        assertEquals(
                VoiceActivityDetector.Event.SPEECH_STARTED,
                detector.accept(frame(2500), FRAME_SAMPLES));
    }

    private static VoiceActivityDetector startedDetector() {
        VoiceActivityDetector detector = new VoiceActivityDetector();
        feed(detector, 5, 8);
        detector.accept(frame(2500), FRAME_SAMPLES);
        assertEquals(
                VoiceActivityDetector.Event.SPEECH_STARTED,
                detector.accept(frame(2500), FRAME_SAMPLES));
        return detector;
    }

    private static void feed(VoiceActivityDetector detector, int count, int amplitude) {
        for (int index = 0; index < count; index++) {
            detector.accept(frame(amplitude), FRAME_SAMPLES);
        }
    }

    private static short[] frame(int amplitude) {
        short[] samples = new short[FRAME_SAMPLES];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) amplitude;
        }
        return samples;
    }
}
