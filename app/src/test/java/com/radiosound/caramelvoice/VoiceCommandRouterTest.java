package com.radiosound.caramelvoice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class VoiceCommandRouterTest {
    @Test
    public void recognizesTimeWordingAndModelSubstitutions() {
        assertType(VoiceCommandRouter.Type.TIME, "what time is it");
        assertType(VoiceCommandRouter.Type.TIME, "MY TIME IS IT");
        assertType(VoiceCommandRouter.Type.TIME, "  the   time now  ");
        assertType(VoiceCommandRouter.Type.TIME, "what is the time");
    }

    @Test
    public void extractsNavigationDestination() {
        VoiceCommandRouter.Command command = VoiceCommandRouter.route("Take me to Times Square");

        assertEquals(VoiceCommandRouter.Type.NAVIGATE_TO, command.type);
        assertEquals("times square", command.argument);
    }

    @Test
    public void extractsMediaSearchWhilePreservingProperNames() {
        VoiceCommandRouter.Command command = VoiceCommandRouter.route(
                "  PLAY   Eric Prydz Opus  ");

        assertEquals(VoiceCommandRouter.Type.PLAY, command.type);
        assertEquals("Eric Prydz Opus", command.argument);
    }

    @Test
    public void selectsAnActionableRecognitionAlternative() {
        VoiceCommandRouter.Command command = VoiceCommandRouter.routeBest(Arrays.asList(
                "plate Eric Prydz Opus",
                "play Eric Prydz Opus",
                "play Eric Prince Opus"));

        assertEquals(VoiceCommandRouter.Type.PLAY, command.type);
        assertEquals("Eric Prydz Opus", command.argument);
    }

    @Test
    public void keepsUnsupportedPhraseAsEcho() {
        VoiceCommandRouter.Command command = VoiceCommandRouter.route("tell me a joke");

        assertEquals(VoiceCommandRouter.Type.ECHO, command.type);
        assertEquals("tell me a joke", command.phrase);
    }

    @Test
    public void recognizesEmptyInput() {
        assertType(VoiceCommandRouter.Type.EMPTY, "  ");
        assertType(VoiceCommandRouter.Type.EMPTY, null);
    }

    private static void assertType(VoiceCommandRouter.Type expected, String phrase) {
        assertEquals(expected, VoiceCommandRouter.route(phrase).type);
    }
}
