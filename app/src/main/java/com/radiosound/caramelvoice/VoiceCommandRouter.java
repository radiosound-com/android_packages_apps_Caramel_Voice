package com.radiosound.caramelvoice;

import java.util.Locale;

/**
 * Maps the small, deliberately deterministic command vocabulary to actions.
 * Keep this class free of Android framework dependencies so recognition
 * wording can be regression-tested on the build host.
 */
final class VoiceCommandRouter {
    enum Type {
        EMPTY,
        TIME,
        NAVIGATE_HOME,
        NAVIGATE_TO,
        OPEN_MAP,
        PLAY,
        ECHO
    }

    static final class Command {
        final Type type;
        final String argument;
        final String phrase;

        private Command(Type type, String argument, String phrase) {
            this.type = type;
            this.argument = argument;
            this.phrase = phrase;
        }
    }

    private VoiceCommandRouter() { }

    static Command routeBest(Iterable<String> alternatives) {
        Command first = null;
        if (alternatives != null) {
            for (String phrase : alternatives) {
                Command command = route(phrase);
                if (first == null && command.type != Type.EMPTY) first = command;
                if (command.type != Type.EMPTY && command.type != Type.ECHO) return command;
            }
        }
        return first == null ? route("") : first;
    }

    static Command route(String phrase) {
        String original = phrase == null ? "" : phrase.trim();
        String normalized = normalize(original);
        if (normalized.isEmpty()) {
            return new Command(Type.EMPTY, "", original);
        }

        if (isTimeQuery(normalized)) {
            return new Command(Type.TIME, "", original);
        }
        if (normalized.contains("navigate home") || normalized.contains("take me home")) {
            return new Command(Type.NAVIGATE_HOME, "home", original);
        }
        if (normalized.startsWith("navigate to ")) {
            return new Command(Type.NAVIGATE_TO,
                    normalized.substring("navigate to ".length()).trim(), original);
        }
        if (normalized.startsWith("take me to ")) {
            return new Command(Type.NAVIGATE_TO,
                    normalized.substring("take me to ".length()).trim(), original);
        }
        if (normalized.startsWith("open map") || normalized.equals("show map")) {
            return new Command(Type.OPEN_MAP, "", original);
        }
        if (normalized.startsWith("play ")) {
            return new Command(Type.PLAY,
                    collapseWhitespace(original.substring("play".length()).trim()), original);
        }
        return new Command(Type.ECHO, "", original);
    }

    private static String normalize(String phrase) {
        return collapseWhitespace(phrase.toLowerCase(Locale.US));
    }

    private static String collapseWhitespace(String phrase) {
        return phrase.replaceAll("\\s+", " ").trim();
    }

    private static boolean isTimeQuery(String normalized) {
        // Accept common wording and the short substitutions observed with the
        // bundled small model, while keeping unrelated phrases as ECHO.
        return normalized.contains("what time")
                || normalized.equals("what is the time")
                || normalized.equals("time")
                || normalized.matches("(?:my|the) time(?: is it| now)?")
                || normalized.endsWith(" time is it");
    }
}
