package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * home_widgets: Android only lets a notification listener see other apps' media sessions, so the launcher is one,
 * granted once over ADB (cmd notification allow_listener com.gathin.tvhome/.MediaListener). It reads no
 * notifications; it follows the media sessions and hands what they play to NowPlaying, which keeps it for the
 * widgets. The system binds it; it does no work of its own between session changes.
 */
public class MediaListener extends NotificationListenerService
        implements MediaSessionManager.OnActiveSessionsChangedListener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> followed = new HashMap<>();
    private MediaSessionManager sessions;

    static ComponentName component(android.content.Context ctx) {
        return new ComponentName(ctx, MediaListener.class);
    }

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(this, component(this), main);
            onActiveSessionsChanged(sessions.getActiveSessions(component(this)));
        } catch (SecurityException ignored) {
            // Listener access was taken away between the bind and here.
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        unfollowAll();
    }

    @Override
    public void onActiveSessionsChanged(List<MediaController> controllers) {
        unfollowAll();
        if (controllers == null) return;
        for (MediaController c : new ArrayList<>(controllers)) {
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    record(c);
                }

                @Override
                public void onPlaybackStateChanged(@NonNull PlaybackState state) {
                    record(c);
                }
            };
            c.registerCallback(cb, main);
            followed.put(c, cb);
            record(c);
        }
    }

    private void unfollowAll() {
        for (Map.Entry<MediaController, MediaController.Callback> e : followed.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        followed.clear();
    }

    private void record(MediaController c) {
        if (!NowPlaying.record(this, c)) return;
        Runnable r = NowPlaying.onChange;
        if (r != null) main.post(r);
    }
}
