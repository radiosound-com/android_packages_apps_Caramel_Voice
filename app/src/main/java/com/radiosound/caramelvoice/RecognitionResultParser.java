/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Parses the JSON result shape shared by Vosk and the Zipformer adapter. */
final class RecognitionResultParser {
    private RecognitionResultParser() {}

    static ArrayList<String> textsFromJson(String value) {
        ArrayList<String> texts = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(value == null ? "{}" : value);
            JSONArray alternatives = json.optJSONArray("alternatives");
            if (alternatives != null) {
                for (int index = 0; index < alternatives.length(); index++) {
                    JSONObject alternative = alternatives.optJSONObject(index);
                    if (alternative != null) addUnique(texts, alternative.optString("text", ""));
                }
            }
            addUnique(texts, json.optString("text", ""));
            addUnique(texts, json.optString("partial", ""));
        } catch (Exception exception) {
            addUnique(texts, value);
        }
        return texts;
    }

    private static void addUnique(ArrayList<String> texts, String value) {
        String text = value == null ? "" : value.trim();
        if (!text.isEmpty() && !texts.contains(text)) texts.add(text);
    }
}
