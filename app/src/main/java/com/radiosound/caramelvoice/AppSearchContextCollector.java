/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads only documents that apps explicitly expose to the assistant through AppSearch. */
final class AppSearchContextCollector {
    private static final String TAG = "CaramelVoice";
    private static final String SOURCE_ID = "appsearch";
    private static final int PAGE_SIZE = 50;
    private static final int MAX_RESULTS = 250;
    private static final int MAX_ALIASES = 8;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppSearchContextCollector() {}

    static void refresh(
            Context context, RecognitionContextIndex index, Runnable completion) {
        AppSearchManager manager = context.getSystemService(AppSearchManager.class);
        if (manager == null) {
            publish(index, new ArrayList<>(), completion);
            return;
        }
        try {
            manager.createGlobalSearchSession(EXECUTOR, result -> {
                GlobalSearchSession session = null;
                try {
                    if (!result.isSuccess() || result.getResultValue() == null) {
                        Log.w(TAG, "Assistant-visible AppSearch context is unavailable: "
                                + result.getErrorMessage());
                        publish(index, new ArrayList<>(), completion);
                        return;
                    }
                    session = result.getResultValue();
                    SearchSpec spec = new SearchSpec.Builder()
                            .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                            .setResultCountPerPage(PAGE_SIZE)
                            .setResultGrouping(SearchSpec.GROUPING_TYPE_PER_PACKAGE, PAGE_SIZE)
                            .setRankingStrategy(
                                    SearchSpec.RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP)
                            .build();
                    SearchResults results = session.search("", spec);
                    new SearchJob(index, session, results, completion).nextPage();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Unable to search assistant-visible AppSearch documents", exception);
                    closeSession(session);
                    publish(index, new ArrayList<>(), completion);
                }
            });
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to open global AppSearch", exception);
            publish(index, new ArrayList<>(), completion);
        }
    }

    private static void publish(
            RecognitionContextIndex index,
            List<RecognitionEntity> entities,
            Runnable completion) {
        index.replaceSource(SOURCE_ID, entities);
        Log.i(TAG, "Indexed " + entities.size() + " assistant-visible AppSearch entities");
        completion.run();
    }

    private static void closeSession(GlobalSearchSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to close the global AppSearch session", exception);
        }
    }

    private static RecognitionEntity toEntity(SearchResult result, int rank) {
        GenericDocument document = result.getGenericDocument();
        RecognitionEntity.Domain domain = domainFor(document.getSchemaType(), document.getPropertyNames());
        if (domain == null) return null;

        ArrayList<PropertyText> values = new ArrayList<>();
        for (String propertyName : document.getPropertyNames()) {
            int priority = propertyPriority(propertyName);
            if (priority == 0) continue;
            Object property;
            try {
                property = document.getProperty(propertyName);
            } catch (RuntimeException exception) {
                continue;
            }
            if (property instanceof String[]) {
                for (String value : (String[]) property) {
                    String cleaned = usablePhrase(value);
                    if (!cleaned.isEmpty()) values.add(new PropertyText(cleaned, priority));
                }
            } else if (property instanceof String) {
                String cleaned = usablePhrase((String) property);
                if (!cleaned.isEmpty()) values.add(new PropertyText(cleaned, priority));
            }
        }
        if (values.isEmpty()) return null;
        values.sort(Comparator.comparingInt((PropertyText value) -> value.priority).reversed());

        String displayText = values.get(0).text;
        ArrayList<String> aliases = new ArrayList<>();
        for (PropertyText value : values) {
            if (!value.text.equals(displayText) && !aliases.contains(value.text)) {
                aliases.add(value.text);
                if (aliases.size() == MAX_ALIASES) break;
            }
        }
        String stableId = result.getPackageName() + ":" + result.getDatabaseName() + ":"
                + document.getNamespace() + ":" + document.getId();
        return new RecognitionEntity(stableId, SOURCE_ID, domain, displayText, aliases, rank);
    }

    static RecognitionEntity.Domain domainFor(String schemaType, Set<String> propertyNames) {
        String schema = schemaType == null ? "" : schemaType.toLowerCase(Locale.US);
        String properties = propertyNames == null
                ? "" : String.join(" ", propertyNames).toLowerCase(Locale.US);
        if (containsAny(schema, "place", "location", "address", "destination", "pointofinterest",
                "point_of_interest", "route")
                || containsAny(properties, "address", "latitude", "longitude", "destination")) {
            return RecognitionEntity.Domain.NAVIGATION;
        }
        if (containsAny(schema, "music", "audio", "song", "track", "artist", "album",
                "playlist", "media")) {
            return RecognitionEntity.Domain.MEDIA;
        }
        if (containsAny(schema, "contact", "person")) {
            return RecognitionEntity.Domain.CONTACT;
        }
        if (containsAny(schema, "application", "appinfo", "installedapp")) {
            return RecognitionEntity.Domain.APP;
        }
        return null;
    }

    private static int propertyPriority(String propertyName) {
        String name = propertyName == null ? "" : propertyName.toLowerCase(Locale.US);
        if (containsAny(name, "displayname", "display_name", "name", "title", "label")) return 100;
        if (containsAny(name, "destination", "place", "location")) return 90;
        if (containsAny(name, "address")) return 80;
        if (containsAny(name, "artist", "album", "playlist")) return 70;
        if (containsAny(name, "description", "subtitle")) return 20;
        return 0;
    }

    private static String usablePhrase(String value) {
        String cleaned = RecognitionEntity.cleanPhrase(value);
        if (cleaned.length() > RecognitionContextIndex.MAX_PHRASE_CHARACTERS
                || cleaned.contains("://")) {
            return "";
        }
        return cleaned;
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private static final class PropertyText {
        final String text;
        final int priority;

        PropertyText(String text, int priority) {
            this.text = text;
            this.priority = priority;
        }
    }

    private static final class SearchJob {
        private final RecognitionContextIndex index;
        private final GlobalSearchSession session;
        private final SearchResults results;
        private final Runnable completion;
        private final ArrayList<RecognitionEntity> entities = new ArrayList<>();
        private boolean finished;

        SearchJob(
                RecognitionContextIndex index,
                GlobalSearchSession session,
                SearchResults results,
                Runnable completion) {
            this.index = index;
            this.session = session;
            this.results = results;
            this.completion = completion;
        }

        void nextPage() {
            if (finished) return;
            try {
                results.getNextPage(EXECUTOR, pageResult -> {
                    if (finished) return;
                    try {
                        if (!pageResult.isSuccess() || pageResult.getResultValue() == null) {
                            Log.w(TAG, "Unable to read AppSearch results: "
                                    + pageResult.getErrorMessage());
                            finish();
                            return;
                        }
                        List<SearchResult> page = pageResult.getResultValue();
                        for (SearchResult result : page) {
                            RecognitionEntity entity = toEntity(
                                    result, 850 - entities.size());
                            if (entity != null) entities.add(entity);
                            if (entities.size() == MAX_RESULTS) break;
                        }
                        if (page.isEmpty() || entities.size() == MAX_RESULTS) {
                            finish();
                        } else {
                            nextPage();
                        }
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "Unable to process AppSearch results", exception);
                        finish();
                    }
                });
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to request the next AppSearch result page", exception);
                finish();
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;
            try {
                results.close();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to close AppSearch results", exception);
            }
            closeSession(session);
            publish(index, entities, completion);
        }
    }
}
