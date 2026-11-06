package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * home_widgets: the last thing each app played, as its media session described it (title, artist, artwork, place),
 * kept in the launcher's own files so the widgets still show it after the app has closed or the TV restarted.
 * MediaListener records it as sessions change; nothing here reaches the network.
 */
final class NowPlaying {
    static final class Item {
        String pkg = "";
        String title = "";
        String subtitle = "";
        boolean playing;
        /** Where playback stood (ms) at positionAt (wall clock ms), and how fast it moves; 0 when not known. */
        long position, positionAt, duration;
        float speed;
        /** When this app last played something (wall clock ms): the widgets' order. */
        long playedAt;

        /** Where playback stands now, moved on by the time since it was recorded while it plays. */
        long positionNow() {
            long p = position;
            if (playing && positionAt > 0) p += (long) ((System.currentTimeMillis() - positionAt) * speed);
            return duration > 0 ? Math.max(0, Math.min(duration, p)) : Math.max(0, p);
        }
    }

    static final String FILE = "now_playing.json";
    /** Artwork is kept as a square this many pixels across, enough for the widget at 1080p. */
    static final int ART_PX = 256;

    /** Set while Home is in front: run (on the main thread) after a recorded change. */
    static volatile Runnable onChange;

    private NowPlaying() { }

    static File artFile(Context ctx, String pkg) {
        return new File(ctx.getFilesDir(), "np_" + pkg + ".jpg");
    }

    static synchronized List<Item> load(Context ctx) {
        List<Item> items = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return items;
        try {
            JSONArray arr = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Item it = new Item();
                it.pkg = o.optString("pkg");
                it.title = o.optString("title");
                it.subtitle = o.optString("subtitle");
                it.playing = o.optBoolean("playing");
                it.position = o.optLong("position");
                it.positionAt = o.optLong("positionAt");
                it.duration = o.optLong("duration");
                it.speed = (float) o.optDouble("speed", 1.0);
                it.playedAt = o.optLong("playedAt");
                if (!it.pkg.isEmpty() && !it.title.isEmpty()) items.add(it);
            }
        } catch (IOException | JSONException ignored) {
            // A damaged cache only means the widgets start empty again.
        }
        return items;
    }

    private static void save(Context ctx, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                arr.put(new JSONObject()
                        .put("pkg", it.pkg).put("title", it.title).put("subtitle", it.subtitle)
                        .put("playing", it.playing).put("position", it.position).put("positionAt", it.positionAt)
                        .put("duration", it.duration).put("speed", it.speed).put("playedAt", it.playedAt));
            }
            File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
            Files.write(tmp.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(ctx.getFilesDir(), FILE))) tmp.delete();
        } catch (IOException | JSONException ignored) {
        }
    }

    /**
     * Keeps what a session says it is playing. A session with no title (Netflix shares none) is left out: a widget
     * with nothing to say would only take the place of one that has something.
     */
    static synchronized boolean record(Context ctx, MediaController c) {
        MediaMetadata md = c.getMetadata();
        if (md == null) return false;
        String title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) return false;
        String subtitle = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST,
                MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        PlaybackState st = c.getPlaybackState();
        String pkg = c.getPackageName();

        List<Item> items = load(ctx);
        Item it = null;
        for (Item x : items) if (x.pkg.equals(pkg)) it = x;
        boolean fresh = it == null || !title.equals(it.title);
        if (it == null) {
            it = new Item();
            it.pkg = pkg;
            items.add(it);
        }
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        it.title = title;
        it.subtitle = subtitle == null ? "" : subtitle;
        it.playing = playing;
        it.duration = Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (st != null && st.getPosition() >= 0) {
            it.position = st.getPosition();
            it.speed = st.getPlaybackSpeed() == 0 ? 1f : st.getPlaybackSpeed();
            it.positionAt = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime());
        }
        // Recency follows playing, not every session callback, so a paused app that reports again keeps its place.
        if (playing || fresh) it.playedAt = System.currentTimeMillis();
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        File artFile = artFile(ctx, pkg);
        if (art != null && (fresh || !artFile.isFile())) saveArt(art, artFile);
        else if (art == null && fresh) artFile.delete();
        save(ctx, items);
        return true;
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    /** The middle square of the artwork, scaled to ART_PX: album art is square, a video still is cropped to one. */
    private static void saveArt(Bitmap src, File out) {
        int side = Math.min(src.getWidth(), src.getHeight());
        if (side <= 0) return;
        Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, ART_PX, ART_PX, true);
        try (FileOutputStream os = new FileOutputStream(out)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, os);
        } catch (IOException ignored) {
        }
        if (scaled != square) scaled.recycle();
        if (square != src) square.recycle();
    }

    /** The widgets: the pinned app's last item first, when there is one, then the most recently played others. */
    static List<Item> pick(List<Item> all, String pinned, int n) {
        List<Item> sorted = new ArrayList<>(all);
        Collections.sort(sorted, (a, b) -> Long.compare(b.playedAt, a.playedAt));
        List<Item> out = new ArrayList<>();
        for (Item it : sorted) if (it.pkg.equals(pinned)) out.add(it);
        for (Item it : sorted) if (!it.pkg.equals(pinned) && out.size() < n) out.add(it);
        return out.size() > n ? out.subList(0, n) : out;
    }
}
