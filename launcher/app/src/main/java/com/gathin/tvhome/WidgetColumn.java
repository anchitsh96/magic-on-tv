package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * home_widgets: three cards stacked left of the app row, each showing what an app last played (NowPlaying): the
 * pinned music app on top, the two most recently played others below. OK plays or pauses the pinned app's session
 * from Home, and opens the app for the others, which resume where they stopped.
 */
final class WidgetColumn {
    interface Host {
        /** Right from a widget: back to the app row. */
        void onWidgetsRight();

        /** A widget took focus: the row's highlight steps back. */
        void onWidgetFocused();
    }

    static final int COUNT = 3;
    /** Card proportions, taken from the prototype (a 208px card in a 660px column). */
    private static final float GAP = 0.087f, RADIUS = 0.125f, PAD = 0.125f, ART = 0.72f;

    private final Context ctx;
    private final Host host;
    private final String pinned;
    private final float density;
    final LinearLayout view;
    private final Card[] cards = new Card[COUNT];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<NowPlaying.Item> shown;
    private int lastFocused;
    private int cardHeight;
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            boolean any = false;
            for (Card c : cards) any |= c.updateProgress();
            if (any) handler.postDelayed(this, 1000);
        }
    };

    WidgetColumn(Context ctx, Host host, String pinned) {
        this.ctx = ctx;
        this.host = host;
        this.pinned = pinned;
        this.density = ctx.getResources().getDisplayMetrics().density;
        view = new LinearLayout(ctx);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        for (int i = 0; i < COUNT; i++) {
            cards[i] = new Card(i);
            view.addView(cards[i].root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    /**
     * Room kept around the cards: a focused card grows 3.5% with its outline, and the frame around the column clips
     * it to the column's own bounds, which cut that growth and the outline off at the sides.
     */
    int margin() {
        return Math.round(view.getResources().getDisplayMetrics().density * 12);
    }

    /** Sizes the column to the poster tile beside it: as tall as the tile, the cards' insides in proportion. */
    void size(int width, int height) {
        int m = margin();
        view.setPadding(m, m, m, m);
        int gap = Math.round(height * GAP / 3f);
        cardHeight = (height - 2 * gap) / COUNT;
        for (int i = 0; i < COUNT; i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cards[i].root.getLayoutParams();
            lp.topMargin = i == 0 ? 0 : gap;
            cards[i].root.setLayoutParams(lp);
            cards[i].size(cardHeight);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && (lp.width != width + 2 * m || lp.height != height + 2 * m)) {
            lp.width = width + 2 * m;
            lp.height = height + 2 * m;
            view.setLayoutParams(lp);
        }
        if (shown != null) bind(shown);
    }

    /** Reads the cache again and shows it; the progress bars move while something plays and Home is in front. */
    void refresh() {
        bind(NowPlaying.pick(NowPlaying.load(ctx), pinned, COUNT));
    }

    private void bind(List<NowPlaying.Item> items) {
        shown = items;
        // Not sized yet (Home resumes before its first layout): size() binds these once the column has its height.
        if (cardHeight <= 0) return;
        for (int i = 0; i < COUNT; i++) cards[i].bind(i < items.size() ? items.get(i) : null);
        startTicking();
    }

    void startTicking() {
        ticking = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stopTicking() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    /** Focus the widget used last (Left from the row's first app). */
    boolean focus() {
        for (int i = 0; i < COUNT; i++) {
            Card c = cards[(lastFocused + i) % COUNT];
            if (c.root.isFocusable() && c.root.requestFocus()) return true;
        }
        return false;
    }

    private void open(NowPlaying.Item it) {
        if (it.pkg.equals(pinned) && togglePlayback(it.pkg)) return;
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(it.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(it.pkg);
        if (i == null) return;
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    /** Plays or pauses the pinned app's own session, without leaving Home. False when it has none to control. */
    private boolean togglePlayback(String pkg) {
        MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
        if (msm == null) return false;
        try {
            for (MediaController c : msm.getActiveSessions(MediaListener.component(ctx))) {
                if (!c.getPackageName().equals(pkg)) continue;
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
                else c.getTransportControls().play();
                return true;
            }
        } catch (SecurityException ignored) {
            // Listener access not granted: the widgets show the cache but cannot control a session.
        }
        return false;
    }

    private final class Card {
        final int index;
        final FrameLayout root;
        final ImageView art;
        final TextView app, title, subtitle;
        final View track, fill;
        final LinearLayout text;
        NowPlaying.Item item;
        int color = 0xFF444444;

        Card(int index) {
            this.index = index;
            root = new FrameLayout(ctx);
            root.setFocusable(true);
            root.setClickable(true);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            art = new ImageView(ctx);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(art, new LinearLayout.LayoutParams(0, 0));

            text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            app = label(R.string.font_medium, 0x99FFFFFF);
            app.setAllCaps(true);
            app.setLetterSpacing(0.12f);
            title = label(R.string.font_medium, 0xFFFFFFFF);
            title.setMaxLines(2);
            title.setSingleLine(false);
            subtitle = label(R.string.font_regular, 0x99FFFFFF);
            text.addView(app);
            text.addView(title);
            text.addView(subtitle);

            FrameLayout bar = new FrameLayout(ctx);
            track = bar;
            fill = new View(ctx);
            fill.setPivotX(0f);
            bar.addView(fill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            text.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));

            root.setOnClickListener(v -> {
                if (item != null) open(item);
            });
            root.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    lastFocused = index;
                    host.onWidgetFocused();
                }
                v.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f).setDuration(220).start();
                v.setForeground(focused ? ring() : null);
            });
            root.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    host.onWidgetsRight();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true;
                // Down from the last card has nowhere to go; Up from the first leaves for the top bar.
                return keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index == COUNT - 1;
            });
        }

        private TextView label(int font, int color) {
            TextView t = new TextView(ctx);
            t.setTypeface(UiFont.get(ctx, font));
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setIncludeFontPadding(false);
            return t;
        }

        void size(int h) {
            int pad = Math.round(h * PAD);
            int artPx = Math.round(h * ART);
            root.setPadding(pad, 0, pad, 0);
            LinearLayout.LayoutParams alp = (LinearLayout.LayoutParams) art.getLayoutParams();
            alp.width = alp.height = artPx;
            alp.rightMargin = Math.round(h * 0.106f);
            art.setLayoutParams(alp);
            app.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.077f);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.12f);
            title.setLineSpacing(0, 1.05f);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.087f);
            ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = Math.round(h * 0.05f);
            ((LinearLayout.LayoutParams) subtitle.getLayoutParams()).topMargin = Math.round(h * 0.03f);
            LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) track.getLayoutParams();
            blp.height = Math.max(2, Math.round(h * 0.02f));
            blp.topMargin = Math.round(h * 0.067f);
            track.setLayoutParams(blp);
        }

        void bind(NowPlaying.Item it) {
            item = it;
            Drawable icon = null;
            if (it != null) {
                try {
                    icon = ctx.getPackageManager().getApplicationIcon(it.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            color = icon != null ? AppAdapter.vividColor(icon) : 0xFF444444;
            float r = cardHeight * RADIUS;
            root.setBackground(card(color, r));
            root.setFocusable(it != null);
            root.setAlpha(it != null ? 1f : 0.5f);
            if (it == null) {
                app.setText("");
                title.setText(R.string.widget_empty_title);
                subtitle.setText(R.string.widget_empty_subtitle);
                art.setImageDrawable(null);
                art.setBackground(rounded(0x22FFFFFF, cardHeight * ART * 0.12f));
                track.setVisibility(View.INVISIBLE);
                return;
            }
            CharSequence name = appName(it.pkg);
            boolean music = it.pkg.equals(pinned);
            String state = music ? (it.playing ? "♫" : "") : ctx.getString(R.string.widget_continue);
            app.setText(state.isEmpty() ? name : name + " · " + state);
            title.setText(it.title);
            subtitle.setText(it.subtitle);
            subtitle.setVisibility(TextUtils.isEmpty(it.subtitle) ? View.GONE : View.VISIBLE);
            int artPx = Math.round(cardHeight * ART);
            art.setBackground(null);
            File f = NowPlaying.artFile(ctx, it.pkg);
            Bitmap b = f.isFile() ? BitmapFactory.decodeFile(f.getPath()) : null;
            art.setImageDrawable(b != null ? roundedBitmap(b, artPx) : iconSquare(icon, color, artPx));
            track.setBackground(rounded(0x24FFFFFF, cardHeight));
            fill.setBackground(rounded(0xFFFFFFFF, cardHeight));
            track.setVisibility(it.duration > 0 ? View.VISIBLE : View.INVISIBLE);
            updateProgress();
        }

        /** Moves the bar to where playback stands; true while it is still moving. */
        boolean updateProgress() {
            if (item == null || item.duration <= 0) return false;
            fill.setScaleX(Math.min(1f, item.positionNow() / (float) item.duration));
            return item.playing;
        }

        private CharSequence appName(String pkg) {
            try {
                PackageManager pm = ctx.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                return pkg;
            }
        }

        /** The prototype's card: near black, the app's colour glowing in from the left edge. */
        private Drawable card(int color, float r) {
            GradientDrawable base = new GradientDrawable();
            base.setColor(0xFF121318);
            base.setCornerRadius(r);
            base.setStroke(Math.max(1, Math.round(density)), 0x12FFFFFF);
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hsv[2] = Math.min(hsv[2], 0.55f);
            int glow = Color.HSVToColor(hsv);
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setColors(new int[] {glow, glow & 0x00FFFFFF});
            g.setGradientCenter(0f, 0.5f);
            g.setGradientRadius(cardHeight * 1.6f);
            g.setCornerRadius(r);
            return new LayerDrawable(new Drawable[] {base, g});
        }

        private Drawable ring() {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(cardHeight * RADIUS);
            d.setStroke(Math.round(3 * density), 0xEBFFFFFF);
            return d;
        }
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    /** Artwork with rounded corners drawn into the bitmap, so nothing clips it while the column animates. */
    private Drawable roundedBitmap(Bitmap src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path p = new Path();
        float r = size * 0.12f;
        p.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
        c.clipPath(p);
        c.drawBitmap(Bitmap.createScaledBitmap(src, size, size, true), 0, 0, null);
        return new BitmapDrawable(ctx.getResources(), out);
    }

    /** No artwork from the session: the app's icon on a square of its colour. */
    private Drawable iconSquare(Drawable icon, int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path p = new Path();
        float r = size * 0.12f;
        p.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
        c.clipPath(p);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = 0.3f;
        c.drawColor(Color.HSVToColor(hsv));
        if (icon != null) {
            int inset = size / 4;
            icon.setBounds(inset, inset, size - inset, size - inset);
            icon.draw(c);
        }
        return new BitmapDrawable(ctx.getResources(), out);
    }
}
