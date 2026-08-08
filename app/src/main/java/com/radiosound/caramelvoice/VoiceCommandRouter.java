/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

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

        if (isHomeNavigation(normalized)) {
            return new Command(Type.NAVIGATE_HOME, "home", original);
        }

        String navigateWithRecovery = recoverNavigationPrefix(normalized);
        String destination = parseNavigationDestination(normalizeForParse(navigateWithRecovery));
        if (destination != null && !destination.isEmpty()) {
            return new Command(Type.NAVIGATE_TO, destination, original);
        }

        if (normalized.startsWith("open map") || normalized.equals("show map")
                || normalized.equals("map")) {
            return new Command(Type.OPEN_MAP, "", original);
        }

        String playArgument = parsePlayArgument(normalizeForParse(normalized));
        if (playArgument != null) {
            return new Command(Type.PLAY, playArgument, original);
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

    private static boolean isHomeNavigation(String normalized) {
        return normalized.contains("navigate home")
                || normalized.contains("take me home")
                || normalized.startsWith("go home")
                || normalized.equals("home");
    }

    private static String parseNavigationDestination(String normalized) {
        if (normalized.startsWith("navigate to ")) {
            return collapseWhitespace(normalized.substring("navigate to ".length()));
        }
        if (normalized.startsWith("take me to ")) {
            return collapseWhitespace(normalized.substring("take me to ".length()));
        }
        if (normalized.startsWith("go to ")) {
            return collapseWhitespace(normalized.substring("go to ".length()));
        }
        return null;
    }

    private static String parsePlayArgument(String normalized) {
        if (normalized.equals("play") || normalized.equals("plays")
                || normalized.equals("play me") || normalized.equals("could you play")
                || normalized.equals("i want to play") || normalized.equals("please play")) {
            return "";
        }
        if (normalized.startsWith("play ")) {
            return collapseWhitespace(normalized.substring("play ".length()));
        }
        if (normalized.startsWith("plays ")) {
            return collapseWhitespace(normalized.substring("plays ".length()));
        }
        if (normalized.startsWith("play me ")) {
            return collapseWhitespace(normalized.substring("play me ".length()));
        }
        if (normalized.startsWith("could you play ")) {
            return collapseWhitespace(normalized.substring("could you play ".length()));
        }
        if (normalized.startsWith("i want to play ")) {
            return collapseWhitespace(normalized.substring("i want to play ".length()));
        }
        if (normalized.startsWith("please play ")) {
            return collapseWhitespace(normalized.substring("please play ".length()));
        }
        return null;
    }

    private static String normalizeForParse(String value) {
        if (value == null) return "";
        return collapseWhitespace(value);
    }

    private static String recoverNavigationPrefix(String normalized) {
        String rebuilt = normalized.replace("the gate to", "navigate to");
        if (rebuilt.startsWith("take me to") && rebuilt.length() > 9
                && !rebuilt.startsWith("take me to ")) {
            rebuilt = rebuilt.replaceFirst("take me to", "navigate to");
        }
        if ((rebuilt.startsWith("go to") || rebuilt.startsWith("go to "))
                && !rebuilt.startsWith("go to my")
                && !rebuilt.equals("go to")) {
            rebuilt = rebuilt.replaceFirst("go to", "navigate to");
        }
        if (rebuilt.startsWith("navigate") && !rebuilt.startsWith("navigate to ")) {
            rebuilt = rebuilt.replaceFirst("navigate", "navigate to");
        }
        if (rebuilt.startsWith("take me ") && !rebuilt.startsWith("take me home")
                && !rebuilt.startsWith("take me to ")) {
            rebuilt = rebuilt.replaceFirst("take me", "navigate to");
        }
        return rebuilt;
    }
}
