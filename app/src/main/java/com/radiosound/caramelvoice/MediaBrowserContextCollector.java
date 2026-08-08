/*
 * Copyright 2026 Radio Sound, Inc.
 * Licensed under the Apache License, Version 2.0.
 */

package com.radiosound.caramelvoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Best-effort bounded crawler for standard Android MediaBrowserService catalogs. */
final class MediaBrowserContextCollector {
    private static final String TAG = "CaramelVoice";
    private static final String SOURCE_ID = "media-browser";
    private static final long TIMEOUT_MS = 8000;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_ITEMS_PER_SERVICE = 300;
    private static final int MAX_BROWSER_SERVICES = 4;
    private static final int MAX_CONCURRENT_BROWSERS = 2;

    private MediaBrowserContextCollector() {}

    static void refresh(
            Context context, RecognitionContextIndex index, Runnable completion) {
        List<ResolveInfo> services;
        try {
            Intent query = new Intent(MediaBrowserService.SERVICE_INTERFACE);
            services = new ArrayList<>(
                    context.getPackageManager().queryIntentServices(query, 0));
            Set<String> activePackages = activeMediaPackages(context);
            services.sort((left, right) -> Integer.compare(
                    priority(right, activePackages), priority(left, activePackages)));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to enumerate media catalogs", exception);
            index.replaceSource(SOURCE_ID, Collections.emptyList());
            completion.run();
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> new BrowserQueue(
                context, index, mainHandler, services, completion).start());
    }

    private static Set<String> activeMediaPackages(Context context) {
        HashSet<String> packages = new HashSet<>();
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return packages;
        try {
            for (MediaController controller : manager.getActiveSessions(null)) {
                packages.add(controller.getPackageName());
            }
        } catch (SecurityException exception) {
            Log.w(TAG, "Unable to rank active media catalogs", exception);
        }
        return packages;
    }

    private static int priority(ResolveInfo resolveInfo, Set<String> activePackages) {
        if (resolveInfo.serviceInfo == null) return Integer.MIN_VALUE;
        int priority = activePackages.contains(resolveInfo.serviceInfo.packageName) ? 100 : 0;
        ApplicationInfo applicationInfo = resolveInfo.serviceInfo.applicationInfo;
        if (applicationInfo != null
                && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            priority += 10;
        }
        return priority;
    }

    private static final class BrowserQueue {
        private final Context context;
        private final RecognitionContextIndex index;
        private final Handler handler;
        private final Runnable completion;
        private final ArrayList<ComponentName> components = new ArrayList<>();
        private final ArrayList<RecognitionEntity> entities = new ArrayList<>();
        private int nextIndex;
        private int running;
        private boolean completed;

        BrowserQueue(
                Context context,
                RecognitionContextIndex index,
                Handler handler,
                List<ResolveInfo> services,
                Runnable completion) {
            this.context = context;
            this.index = index;
            this.handler = handler;
            this.completion = completion;
            HashSet<String> seenComponents = new HashSet<>();
            for (ResolveInfo resolveInfo : services) {
                if (resolveInfo.serviceInfo == null) continue;
                ComponentName component = new ComponentName(
                        resolveInfo.serviceInfo.packageName,
                        resolveInfo.serviceInfo.name);
                if (seenComponents.add(component.flattenToShortString())) {
                    components.add(component);
                    if (components.size() == MAX_BROWSER_SERVICES) break;
                }
            }
        }

        void start() {
            startNext();
        }

        private void startNext() {
            while (running < MAX_CONCURRENT_BROWSERS && nextIndex < components.size()) {
                ComponentName component = components.get(nextIndex++);
                running++;
                new BrowserJob(context, handler, component, this::jobFinished).start();
            }
            if (!completed && running == 0 && nextIndex >= components.size()) {
                completed = true;
                index.replaceSource(SOURCE_ID, entities);
                Log.i(TAG, "Completed bounded scan of " + components.size()
                        + " media catalogs with " + entities.size() + " entities");
                completion.run();
            }
        }

        private void jobFinished(List<RecognitionEntity> jobEntities) {
            entities.addAll(jobEntities);
            running--;
            startNext();
        }
    }

    private static final class BrowserJob {
        private final Context context;
        private final Handler handler;
        private final ComponentName component;
        private final Consumer<List<RecognitionEntity>> completion;
        private final ArrayList<RecognitionEntity> entities = new ArrayList<>();
        private final Set<String> visited = new HashSet<>();
        private final Runnable timeout = () -> finish("timeout");
        private MediaBrowser browser;
        private int pendingSubscriptions;
        private boolean finished;

        BrowserJob(
                Context context,
                Handler handler,
                ComponentName component,
                Consumer<List<RecognitionEntity>> completion) {
            this.context = context;
            this.handler = handler;
            this.component = component;
            this.completion = completion;
        }

        void start() {
            MediaBrowser.ConnectionCallback callback = new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    if (finished || browser == null) return;
                    subscribe(browser.getRoot(), 0);
                }

                @Override public void onConnectionSuspended() {
                    finish("suspended");
                }

                @Override public void onConnectionFailed() {
                    finish("connection failed");
                }
            };
            try {
                browser = new MediaBrowser(context, component, callback, null);
                handler.postDelayed(timeout, TIMEOUT_MS);
                browser.connect();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to connect to media catalog " + component, exception);
                finish("connect exception");
            }
        }

        private void subscribe(String parentId, int depth) {
            if (finished || browser == null || parentId == null || parentId.isEmpty()) return;
            if (!visited.add(parentId) || depth > MAX_DEPTH
                    || entities.size() >= MAX_ITEMS_PER_SERVICE) return;
            pendingSubscriptions++;
            MediaBrowser.SubscriptionCallback callback = new MediaBrowser.SubscriptionCallback() {
                @Override public void onChildrenLoaded(
                        String loadedParentId, List<MediaBrowser.MediaItem> children) {
                    if (finished) return;
                    try {
                        browser.unsubscribe(loadedParentId, this);
                        for (MediaBrowser.MediaItem item : children) {
                            if (entities.size() >= MAX_ITEMS_PER_SERVICE) break;
                            addItem(item, depth);
                        }
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "Unable to read media catalog " + component, exception);
                    } finally {
                        subscriptionComplete();
                    }
                }

                @Override public void onError(String failedParentId) {
                    if (finished) return;
                    try {
                        browser.unsubscribe(failedParentId, this);
                    } catch (RuntimeException exception) {
                        Log.w(TAG, "Unable to unsubscribe from " + component, exception);
                    } finally {
                        subscriptionComplete();
                    }
                }
            };
            try {
                browser.subscribe(parentId, callback);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to browse " + component + " parent " + parentId, exception);
                pendingSubscriptions--;
                maybeFinish();
            }
        }

        private void addItem(MediaBrowser.MediaItem item, int depth) {
            MediaDescription description = item.getDescription();
            String mediaId = description.getMediaId();
            String title = RecognitionContextRepository.text(description.getTitle());
            String artist = RecognitionContextRepository.text(description.getSubtitle());
            String album = RecognitionContextRepository.text(description.getDescription());
            Bundle extras = description.getExtras();
            if (extras != null) {
                if (artist.isEmpty()) {
                    artist = RecognitionContextRepository.text(
                            extras.getCharSequence(MediaMetadata.METADATA_KEY_ARTIST));
                }
                if (album.isEmpty()) {
                    album = RecognitionContextRepository.text(
                            extras.getCharSequence(MediaMetadata.METADATA_KEY_ALBUM));
                }
            }

            if (item.isPlayable()) {
                RecognitionContextRepository.addMediaEntity(
                        entities,
                        component.flattenToShortString() + ":"
                                + (mediaId == null ? entities.size() : mediaId),
                        SOURCE_ID,
                        title,
                        artist,
                        album,
                        700 - depth * 10 - entities.size());
            } else if (item.isBrowsable() && !title.isEmpty()) {
                entities.add(new RecognitionEntity(
                        component.flattenToShortString() + ":browse:"
                                + (mediaId == null ? entities.size() : mediaId),
                        SOURCE_ID,
                        RecognitionEntity.Domain.MEDIA,
                        title,
                        450 - depth * 10 - entities.size()));
            }

            if (item.isBrowsable() && mediaId != null && depth < MAX_DEPTH) {
                subscribe(mediaId, depth + 1);
            }
        }

        private void subscriptionComplete() {
            pendingSubscriptions--;
            maybeFinish();
        }

        private void maybeFinish() {
            if (pendingSubscriptions == 0) finish("complete");
        }

        private void finish(String reason) {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(timeout);
            if (browser != null) {
                try {
                    browser.disconnect();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "Unable to disconnect media catalog " + component, exception);
                }
            }
            Log.i(TAG, "Indexed " + entities.size() + " media catalog entities from "
                    + component.flattenToShortString() + " (" + reason + ")");
            ArrayList<RecognitionEntity> result = new ArrayList<>(entities);
            handler.post(() -> completion.accept(result));
        }
    }
}
