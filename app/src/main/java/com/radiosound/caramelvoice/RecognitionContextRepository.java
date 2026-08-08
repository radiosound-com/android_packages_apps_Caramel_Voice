/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Collects app-neutral recognition context from Android's standard data surfaces. */
final class RecognitionContextRepository {
    private static final String TAG = "CaramelVoice";
    private static final String LEARNED_PREFERENCES = "recognition_context";
    private static final int MAX_MEDIA_STORE_ENTITIES = 500;
    private static final int MAX_PLAYLIST_ENTITIES = 120;
    private static final int MAX_LEARNED_ENTITIES_PER_DOMAIN = 64;
    private static final long MIN_REFRESH_INTERVAL_MS = 60_000;
    private static final long MEDIA_STORE_REFRESH_DEBOUNCE_MS = 1_500;

    private static final RecognitionContextIndex INDEX = new RecognitionContextIndex();
    private static final ExecutorService REFRESHER = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArrayList<Runnable> CHANGE_LISTENERS =
            new CopyOnWriteArrayList<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicLong LAST_REFRESH_ELAPSED_MS = new AtomicLong();
    private static volatile Context APPLICATION_CONTEXT;
    private static final Object MEDIA_OBSERVER_LOCK = new Object();
    private static final Runnable MEDIA_STORE_REFRESH = () -> {
        Context applicationContext = APPLICATION_CONTEXT;
        if (applicationContext != null) refresh(applicationContext, true);
    };
    private static Handler mediaObserverHandler;
    private static ContentObserver mediaStoreObserver;

    private RecognitionContextRepository() {}

    static void preload(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (STARTED.compareAndSet(false, true)) {
            APPLICATION_CONTEXT = applicationContext;
            seedBuiltInVocabulary();
            registerMediaStoreObserver(applicationContext);
            refresh(applicationContext);
        }
    }

    static void refresh(Context context) {
        refresh(context, false);
    }

    private static void refresh(Context context, boolean force) {
        Context applicationContext = context.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        while (true) {
            long previous = LAST_REFRESH_ELAPSED_MS.get();
            if (!force && previous != 0 && now - previous < MIN_REFRESH_INTERVAL_MS) return;
            if (LAST_REFRESH_ELAPSED_MS.compareAndSet(previous, now)) break;
        }
        REFRESHER.execute(() -> {
            refreshLearned(applicationContext, true);
            refreshActiveMedia(applicationContext, true);
            refreshMediaStore(applicationContext, true);
            AtomicInteger pendingCollectors = new AtomicInteger(2);
            Runnable collectorFinished = () -> {
                if (pendingCollectors.decrementAndGet() == 0) notifyChanged();
            };
            AppSearchContextCollector.refresh(applicationContext, INDEX, collectorFinished);
            MediaBrowserContextCollector.refresh(applicationContext, INDEX, collectorFinished);
        });
    }

    /** Refreshes cheap resolver context and asks the backend to rebuild when it is safe. */
    static void refreshForeground(Context context) {
        Context applicationContext = context.getApplicationContext();
        registerMediaStoreObserver(applicationContext);
        REFRESHER.execute(() -> {
            refreshLearned(applicationContext, false);
            refreshActiveMedia(applicationContext, false);
            // SherpaModelRepository defers this notification while a stream lease is active,
            // then reloads the hotword graph as soon as the command is complete. Without the
            // notification, newly active titles are only available to post-ASR resolution and
            // never improve the next decoder session.
            notifyChanged();
        });
        // MediaStore/AppSearch/catalog context is intentionally throttled. This catches changes
        // from sources that do not expose a persistent observer, while the MediaStore observer
        // below handles ordinary library and playlist edits immediately after debounce.
        refreshIfStale(applicationContext);
    }

    private static void refreshIfStale(Context context) {
        long lastRefresh = LAST_REFRESH_ELAPSED_MS.get();
        if (lastRefresh == 0
                || SystemClock.elapsedRealtime() - lastRefresh >= MIN_REFRESH_INTERVAL_MS) {
            refresh(context);
        }
    }

    private static void registerMediaStoreObserver(Context context) {
        synchronized (MEDIA_OBSERVER_LOCK) {
            if (mediaStoreObserver != null) return;
            try {
                mediaObserverHandler = new Handler(Looper.getMainLooper());
                ContentObserver observer = new ContentObserver(mediaObserverHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        scheduleMediaStoreRefresh();
                    }
                };
                ContentResolver resolver = context.getContentResolver();
                resolver.registerContentObserver(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer);
                resolver.registerContentObserver(
                        MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, true, observer);
                mediaStoreObserver = observer;
                Log.i(TAG, "Registered MediaStore audio and playlist context observer");
            } catch (RuntimeException exception) {
                mediaObserverHandler = null;
                Log.w(TAG, "Unable to register MediaStore context observer", exception);
            }
        }
    }

    private static void scheduleMediaStoreRefresh() {
        synchronized (MEDIA_OBSERVER_LOCK) {
            if (mediaObserverHandler == null) return;
            mediaObserverHandler.removeCallbacks(MEDIA_STORE_REFRESH);
            mediaObserverHandler.postDelayed(
                    MEDIA_STORE_REFRESH, MEDIA_STORE_REFRESH_DEBOUNCE_MS);
        }
    }

    static RecognitionContextIndex.Snapshot snapshot(Context context) {
        preload(context);
        return INDEX.snapshot();
    }

    static RecognitionContextIndex.Snapshot snapshot(Context context, int maxPhrases) {
        preload(context);
        return INDEX.snapshot(maxPhrases);
    }

    static void addChangeListener(Runnable listener) {
        if (listener != null) CHANGE_LISTENERS.addIfAbsent(listener);
    }

    static void notifyChanged() {
        for (Runnable listener : CHANGE_LISTENERS) listener.run();
    }

    static void recordSuccessful(
            Context context, RecognitionEntity.Domain domain, String displayText) {
        String cleaned = RecognitionEntity.cleanPhrase(displayText);
        if (cleaned.isEmpty()) return;
        Context applicationContext = context.getApplicationContext();
        REFRESHER.execute(() -> {
            SharedPreferences preferences = applicationContext.getSharedPreferences(
                    LEARNED_PREFERENCES, Context.MODE_PRIVATE);
            String key = "learned." + domain.name();
            ArrayList<String> values = new ArrayList<>(
                    preferences.getStringSet(key, Collections.emptySet()));
            values.removeIf(value -> RecognitionContextIndex.normalize(value)
                    .equals(RecognitionContextIndex.normalize(cleaned)));
            values.add(0, cleaned);
            if (values.size() > MAX_LEARNED_ENTITIES_PER_DOMAIN) {
                values.subList(MAX_LEARNED_ENTITIES_PER_DOMAIN, values.size()).clear();
            }
            preferences.edit().putStringSet(key, new HashSet<>(values)).apply();
            refreshLearned(applicationContext, false);
        });
    }

    private static void seedBuiltInVocabulary() {
        List<RecognitionEntity> entities = Arrays.asList(
                new RecognitionEntity("time", "builtin", RecognitionEntity.Domain.GENERAL,
                        "what time is it", 1000),
                new RecognitionEntity("play", "builtin", RecognitionEntity.Domain.GENERAL,
                        "play", 1000),
                new RecognitionEntity("navigate", "builtin", RecognitionEntity.Domain.GENERAL,
                        "navigate to", 1000),
                new RecognitionEntity("home", "builtin", RecognitionEntity.Domain.NAVIGATION,
                        "take me home", Arrays.asList("navigate home"), 1000));
        publishSource("builtin", entities);
    }

    private static void refreshLearned(Context context, boolean notifyModel) {
        SharedPreferences preferences = context.getSharedPreferences(
                LEARNED_PREFERENCES, Context.MODE_PRIVATE);
        ArrayList<RecognitionEntity> entities = new ArrayList<>();
        for (RecognitionEntity.Domain domain : RecognitionEntity.Domain.values()) {
            Set<String> values = preferences.getStringSet(
                    "learned." + domain.name(), Collections.emptySet());
            int rank = 900;
            for (String value : values) {
                String normalized = RecognitionContextIndex.normalize(value);
                if (normalized.isEmpty()) continue;
                entities.add(new RecognitionEntity(
                        domain.name() + ":" + normalized,
                        "learned",
                        domain,
                        value,
                        rank--));
            }
        }
        publishSource("learned", entities, notifyModel);
    }

    private static void refreshActiveMedia(Context context, boolean notifyModel) {
        ArrayList<RecognitionEntity> entities = new ArrayList<>();
        if (context.checkSelfPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
                != PackageManager.PERMISSION_GRANTED) {
            publishSource("active-media", entities, notifyModel);
            Log.i(TAG, "Active media context skipped: MEDIA_CONTENT_CONTROL is not granted");
            return;
        }
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) {
            publishSource("active-media", entities, notifyModel);
            return;
        }
        try {
            int rank = 950;
            for (MediaController controller : manager.getActiveSessions(null)) {
                MediaMetadata metadata = controller.getMetadata();
                if (metadata == null) continue;
                String title = text(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
                String artist = text(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST));
                String album = text(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
                addMediaEntity(
                        entities,
                        "session:" + controller.getPackageName() + ":" + rank,
                        "active-media",
                        title,
                        artist,
                        album,
                        rank--);
            }
        } catch (SecurityException exception) {
            Log.w(TAG, "Active media context is unavailable", exception);
        }
        publishSource("active-media", entities, notifyModel);
        Log.i(TAG, "Indexed " + entities.size() + " active media entities");
    }

    private static void refreshMediaStore(Context context, boolean notifyModel) {
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            publishSource("media-store", Collections.emptyList(), notifyModel);
            Log.i(TAG, "MediaStore context skipped: READ_MEDIA_AUDIO is not granted");
            return;
        }
        if (MediaStore.getExternalVolumeNames(context).isEmpty()) {
            publishSource("media-store", Collections.emptyList(), notifyModel);
            Log.i(TAG, "MediaStore context skipped: no external media volume");
            return;
        }

        ArrayList<RecognitionEntity> entities = new ArrayList<>();
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM
        };
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Audio.Media.IS_MUSIC + " != 0",
                null,
                MediaStore.Audio.Media.DATE_MODIFIED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int rank = 800;
                while (cursor.moveToNext() && entities.size() < MAX_MEDIA_STORE_ENTITIES) {
                    addMediaEntity(
                            entities,
                            "media-store:" + cursor.getLong(idColumn),
                            "media-store",
                            cursor.getString(titleColumn),
                            cursor.getString(artistColumn),
                            cursor.getString(albumColumn),
                            rank--);
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to index MediaStore audio", exception);
        }
        publishSource("media-store", entities, notifyModel);
        Log.i(TAG, "Indexed " + entities.size() + " MediaStore audio entities");

        refreshMediaStorePlaylists(context, entities, notifyModel);
    }

    private static void refreshMediaStorePlaylists(
            Context context, List<RecognitionEntity> entities, boolean notifyModel) {
        if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ArrayList<RecognitionEntity> playlistEntities = new ArrayList<>();
        String[] projection = {
                MediaStore.Audio.Playlists._ID,
                MediaStore.Audio.Playlists.NAME
        };
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Audio.Playlists._ID + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME);
                while (cursor.moveToNext() && playlistEntities.size() < MAX_PLAYLIST_ENTITIES) {
                    String name = text(cursor.getString(nameColumn));
                    if (name.isEmpty()) continue;
                    addMediaEntity(
                            playlistEntities,
                            "media-store:playlist:" + cursor.getLong(idColumn),
                            "media-store",
                            name,
                            "",
                            "",
                            750 - playlistEntities.size());
                }
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to index MediaStore playlists", exception);
        }

        if (!playlistEntities.isEmpty()) {
            entities.addAll(playlistEntities);
            publishSource("media-store", entities, notifyModel);
            Log.i(TAG, "Indexed " + playlistEntities.size() + " MediaStore playlists");
        }
    }

    static void addMediaEntity(
            List<RecognitionEntity> output,
            String stableId,
            String sourceId,
            String titleValue,
            String artistValue,
            String albumValue,
            int rank) {
        String title = text(titleValue);
        String artist = text(artistValue);
        String album = text(albumValue);
        if (title.isEmpty() && artist.isEmpty()) return;

        boolean titleAlreadyNamesArtist = !title.isEmpty() && !artist.isEmpty()
                && RecognitionContextIndex.normalize(title)
                        .contains(RecognitionContextIndex.normalize(artist));
        String displayText = artist.isEmpty() || titleAlreadyNamesArtist ? title
                : title.isEmpty() ? artist
                : artist + " " + title;
        ArrayList<String> aliases = new ArrayList<>();
        if (!title.isEmpty()) aliases.add(title);
        if (!artist.isEmpty()) aliases.add(artist);
        if (!album.isEmpty()) aliases.add(album);
        if (!title.isEmpty() && !artist.isEmpty()) aliases.add(title + " by " + artist);
        output.add(new RecognitionEntity(
                stableId,
                sourceId,
                RecognitionEntity.Domain.MEDIA,
                displayText,
                aliases,
                rank));
    }

    static String text(CharSequence value) {
        return RecognitionEntity.cleanPhrase(value == null ? "" : value.toString());
    }

    private static void publishSource(String sourceId, List<RecognitionEntity> entities) {
        publishSource(sourceId, entities, true);
    }

    private static void publishSource(
            String sourceId, List<RecognitionEntity> entities, boolean notifyModel) {
        INDEX.replaceSource(sourceId, entities);
        if (notifyModel) notifyChanged();
    }
}
