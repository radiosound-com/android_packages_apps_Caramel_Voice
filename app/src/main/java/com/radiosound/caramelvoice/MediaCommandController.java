/*
 * Copyright (C) 2026 Radio Sound, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.radiosound.caramelvoice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

import java.util.List;

/** Dispatches voice media searches through Android's standard media session API. */
final class MediaCommandController {
    private static final String TAG = "CaramelVoice";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final long BROWSER_CONNECTION_TIMEOUT_MS = 4000;

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
    private MediaBrowser pendingBrowser;
    private Runnable pendingTimeout;
    private Callback pendingCallback;

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

        MediaController controller = findSpotifySearchController();
        if (controller != null && dispatch(controller, trimmedQuery)) {
            callback.onComplete(Result.STARTED, controller.getPackageName());
            return;
        }
        connectToSpotify(trimmedQuery, callback);
    }

    void close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelPending();
        } else {
            mainHandler.post(this::cancelPending);
        }
    }

    private MediaController findSpotifySearchController() {
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        if (manager == null) return null;
        try {
            List<MediaController> controllers = manager.getActiveSessions(null);
            for (MediaController controller : controllers) {
                PlaybackState state = controller.getPlaybackState();
                boolean supportsSearch = state != null
                        && (state.getActions() & PlaybackState.ACTION_PLAY_FROM_SEARCH) != 0;
                if (!supportsSearch) continue;
                if (SPOTIFY_PACKAGE.equals(controller.getPackageName())) return controller;
            }
            return null;
        } catch (SecurityException exception) {
            Log.e(TAG, "MEDIA_CONTENT_CONTROL is not granted", exception);
            return null;
        }
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

    private void connectToSpotify(String query, Callback callback) {
        Intent serviceIntent = new Intent(MediaBrowserService.SERVICE_INTERFACE)
                .setPackage(SPOTIFY_PACKAGE);
        List<ResolveInfo> services = context.getPackageManager()
                .queryIntentServices(serviceIntent, 0);
        if (services.isEmpty() || services.get(0).serviceInfo == null) {
            callback.onComplete(Result.PLAYER_NOT_FOUND, "");
            return;
        }

        ComponentName component = new ComponentName(
                services.get(0).serviceInfo.packageName,
                services.get(0).serviceInfo.name);
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
                            completePending(
                                    dispatch(controller, query) ? Result.STARTED : Result.FAILED,
                                    controller.getPackageName());
                        } catch (RuntimeException exception) {
                            Log.w(TAG, "Unable to control Spotify browser session", exception);
                            completePending(Result.FAILED, SPOTIFY_PACKAGE);
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        completePending(Result.FAILED, SPOTIFY_PACKAGE);
                    }

                    @Override
                    public void onConnectionFailed() {
                        completePending(Result.FAILED, SPOTIFY_PACKAGE);
                    }
                };

        MediaBrowser browser = new MediaBrowser(context, component, connectionCallback, null);
        browserHolder[0] = browser;
        pendingBrowser = browser;
        pendingCallback = callback;
        pendingTimeout = () -> completePending(Result.FAILED, SPOTIFY_PACKAGE);
        mainHandler.postDelayed(pendingTimeout, BROWSER_CONNECTION_TIMEOUT_MS);
        try {
            browser.connect();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to connect to Spotify media browser", exception);
            completePending(Result.FAILED, SPOTIFY_PACKAGE);
        }
    }

    private void completePending(Result result, String playerPackage) {
        Callback callback = pendingCallback;
        cancelPending();
        if (callback != null) callback.onComplete(result, playerPackage);
    }

    private void cancelPending() {
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
        pendingCallback = null;
    }
}
