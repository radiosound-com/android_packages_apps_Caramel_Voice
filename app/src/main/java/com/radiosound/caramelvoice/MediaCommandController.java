/*
 * Copyright (C) 2026 Radio Sound, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.radiosound.caramelvoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.media.MediaBrowserService;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Dispatches voice media searches through Android's standard media session API. */
final class MediaCommandController {
    private static final String TAG = "CaramelVoice";
    private static final long BROWSER_CONNECTION_TIMEOUT_MS = 1500;
    private static final int MAX_BROWSER_CANDIDATES = 4;

    enum Result {
        STARTED,
        PLAYER_NOT_FOUND,
        FAILED
    }

    interface Callback {
        void onComplete(Result result, String playerPackage);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<ComponentName> pendingComponents = new ArrayList<>();
    private MediaBrowser pendingBrowser;
    private Runnable pendingTimeout;
    private Callback pendingCallback;
    private String pendingQuery;
    private int pendingComponentIndex;

    MediaCommandController(Context context) {
        this.context = context.getApplicationContext();
    }

    void playFromSearch(String query, Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> playFromSearch(query, callback));
            return;
        }
        cancelPending();
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            callback.onComplete(Result.FAILED, "");
            return;
        }

        MediaController controller = findSearchController();
        if (controller != null && dispatch(controller, trimmedQuery)) {
            callback.onComplete(Result.STARTED, controller.getPackageName());
            return;
        }
        connectToCompatibleBrowser(trimmedQuery, callback);
    }

    void close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPending();
        } else {
            mainHandler.post(this::cancelPending);
        }
    }

    private MediaController findSearchController() {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return null;
        try {
            MediaController best = null;
            int bestScore = Integer.MIN_VALUE;
            for (MediaController controller : manager.getActiveSessions(null)) {
                if (!supportsSearch(controller)) continue;
                int score = sessionScore(controller);
                if (best == null || score > bestScore) {
                    best = controller;
                    bestScore = score;
                }
            }
            return best;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to inspect active media sessions", exception);
            return null;
        }
    }

    private static boolean supportsSearch(MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        return state != null
                && (state.getActions() & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0;
    }

    private static int sessionScore(MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return 0;
        int score;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
                score = 300;
                break;
            case PlaybackState.STATE_PAUSED:
                score = 200;
                break;
            case PlaybackState.STATE_STOPPED:
                score = 100;
                break;
            default:
                score = 0;
                break;
        }
        if (controller.getMetadata() != null) score += 10;
        return score;
    }

    private boolean dispatch(MediaController controller, String query) {
        try {
            controller.getTransportControls().playFromSearch(query, new Bundle());
            Log.i(TAG, "MEDIA_SEARCH: " + query + " -> " + controller.getPackageName());
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to search media session " + controller.getPackageName(), exception);
            return false;
        }
    }

    private void connectToCompatibleBrowser(String query, Callback callback) {
        List<ResolveInfo> services;
        try {
            services = new ArrayList<>(context.getPackageManager().queryIntentServices(
                    new Intent(MediaBrowserService.SERVICE_INTERFACE), 0));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to enumerate compatible media players", exception);
            callback.onComplete(Result.FAILED, "");
            return;
        }
        Set<String> activePackages = activeMediaPackages();
        services.sort((left, right) -> Integer.compare(
                browserPriority(right, activePackages),
                browserPriority(left, activePackages)));

        HashSet<String> seenComponents = new HashSet<>();
        for (ResolveInfo resolveInfo : services) {
            if (resolveInfo.serviceInfo == null) continue;
            ComponentName component = new ComponentName(
                    resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            if (seenComponents.add(component.flattenToShortString())) {
                pendingComponents.add(component);
                if (pendingComponents.size() == MAX_BROWSER_CANDIDATES) break;
            }
        }
        if (pendingComponents.isEmpty()) {
            callback.onComplete(Result.PLAYER_NOT_FOUND, "");
            return;
        }

        pendingQuery = query;
        pendingCallback = callback;
        pendingComponentIndex = 0;
        tryNextBrowser();
    }

    private Set<String> activeMediaPackages() {
        HashSet<String> packages = new HashSet<>();
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return packages;
        try {
            for (MediaController controller : manager.getActiveSessions(null)) {
                packages.add(controller.getPackageName());
            }
        } catch (SecurityException exception) {
            Log.w(TAG, "Unable to rank active media browsers", exception);
        }
        return packages;
    }

    private static int browserPriority(ResolveInfo resolveInfo, Set<String> activePackages) {
        if (resolveInfo.serviceInfo == null) return Integer.MIN_VALUE;
        int priority = activePackages.contains(resolveInfo.serviceInfo.packageName) ? 100 : 0;
        ApplicationInfo applicationInfo = resolveInfo.serviceInfo.applicationInfo;
        if (applicationInfo != null
                && (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            priority += 10;
        }
        return priority;
    }

    private void tryNextBrowser() {
        disconnectPendingBrowser();
        if (pendingCallback == null) return;
        if (pendingComponentIndex >= pendingComponents.size()) {
            completePending(Result.PLAYER_NOT_FOUND, "");
            return;
        }

        ComponentName component = pendingComponents.get(pendingComponentIndex++);
        final MediaBrowser[] browserHolder = new MediaBrowser[1];
        MediaBrowser.ConnectionCallback connectionCallback =
                new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        MediaBrowser browser = browserHolder[0];
                        if (browser == null || browser != pendingBrowser) return;
                        try {
                            MediaController controller =
                                    new MediaController(context, browser.getSessionToken());
                            if (supportsSearch(controller)
                                    && dispatch(controller, pendingQuery)) {
                                completePending(Result.STARTED, controller.getPackageName());
                            } else {
                                tryNextBrowser();
                            }
                        } catch (RuntimeException exception) {
                            Log.w(TAG, "Unable to control media browser " + component, exception);
                            tryNextBrowser();
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        if (browserHolder[0] == pendingBrowser) tryNextBrowser();
                    }

                    @Override
                    public void onConnectionFailed() {
                        if (browserHolder[0] == pendingBrowser) tryNextBrowser();
                    }
                };

        MediaBrowser browser = new MediaBrowser(context, component, connectionCallback, null);
        browserHolder[0] = browser;
        pendingBrowser = browser;
        pendingTimeout = () -> {
            if (browser == pendingBrowser) tryNextBrowser();
        };
        mainHandler.postDelayed(pendingTimeout, BROWSER_CONNECTION_TIMEOUT_MS);
        try {
            browser.connect();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to connect to media browser " + component, exception);
            tryNextBrowser();
        }
    }

    private void completePending(Result result, String playerPackage) {
        Callback callback = pendingCallback;
        cancelPending();
        if (callback != null) callback.onComplete(result, playerPackage);
    }

    private void cancelPending() {
        disconnectPendingBrowser();
        pendingCallback = null;
        pendingQuery = null;
        pendingComponentIndex = 0;
        pendingComponents.clear();
    }

    private void disconnectPendingBrowser() {
        if (pendingTimeout != null) {
            mainHandler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
        if (pendingBrowser != null) {
            MediaBrowser browser = pendingBrowser;
            pendingBrowser = null;
            try {
                browser.disconnect();
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to disconnect media browser", exception);
            }
        }
    }
}
