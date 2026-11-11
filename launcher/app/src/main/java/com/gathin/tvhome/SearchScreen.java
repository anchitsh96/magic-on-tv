package com.gathin.tvhome;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * title_search: the search screen, the search pill's own OTT home. A billboard for whatever has focus, then rows:
 * Continue watching, the asks the viewer pinned, and what's new, best rated and popular on the TV's apps, all from
 * JustWatch each time it opens. Asking (the bar at the top, or the pill on Home) shows what was understood, ways to
 * narrow it down, and every match on the TV's apps; an ask can be pinned as a row. One pick shows one title at a
 * time. OK on a poster shows where to watch it and Seen it, which hides a title from every row.
 *
 * The screen takes the remote's keys itself (dispatchKeyEvent) and draws focus on its own views, so Home's focus
 * stays where it was underneath. Sizes follow the prototype's 1920x1080 canvas, scaled to the screen's height.
 */
final class SearchScreen {
    interface Host {
        /** Ask once for the microphone; answer with micAnswered(). */
        void requestMic();

        /** The screen closed: Home takes focus again. */
        void onClosed();
    }

    private static final int BG = 0xFF06070A, CARD = 0xFF15161C, MUTED = 0x9EFFFFFF, FAINT = 0x66FFFFFF, LINE = 0x1AFFFFFF;
    private static final int IMDB = 0xFFF5C518, INK = 0xFF111111, PANEL = 0xF00D0E13;
    private static final String PREFS = "title_search", SEEN = "seen", PINNED = "pinned";
    private static final int CONT = 0, CHIPS = 1, ASK = 2, ROW = 3;
    /** Prototype sizes: a poster and the gap after it, a Continue card and its gap. */
    private static final int POSTER_W = 200, POSTER_H = 300, GAP = 22, LAND_W = 420, LAND_H = 236;

    private final Context ctx;
    private final Host host;
    private final String country, apiKey, leaveOut;
    private final PackageManager pm;
    private final float unit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final Voice voice;
    private final SharedPreferences prefs;
    private final LruCache<String, Bitmap> posters = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final LruCache<String, Bitmap> backdrops = new LruCache<String, Bitmap>(5);

    final FrameLayout view;
    private final ImageView backdrop;
    private final FrameLayout askBar;
    private final FrameLayout askMic;
    private final ImageView askMicIcon;
    private final TextView askText;
    private final EditText askField;
    private final Voice.Pulse askPulse;
    private final TextView onePick;
    private final TextView infoCaps, infoTitle, infoMeta, infoDesc, infoOn;
    private final android.widget.ScrollView rowsBox;
    private final LinearLayout track;
    private final FrameLayout over;

    /** The rows as shown, top to bottom, and where focus is: -1 is the top bar (0 the ask bar, 1 One pick). */
    private final List<RowState> rows = new ArrayList<>();
    private int r, topF;
    private AskState ask;
    private Over overState;
    private boolean typing, listening;
    /** Bumped each time the screen opens or asks, so answers that arrive late are dropped. */
    private int seq;
    private String backdropFor = "";

    /** One row on the screen. */
    private static final class RowState {
        final int kind;
        String label;
        boolean pinned;
        /** Where it came from: a standard or pinned row's filter, or the ask. */
        Discover.Row def;
        int pinIndex = -1;
        final List<TitleSearch.Title> items = new ArrayList<>();
        final List<NowPlaying.Item> cont = new ArrayList<>();
        boolean loading = true;
        int col;
        LinearLayout strip;
        TextView count;
        final List<View> cells = new ArrayList<>();

        RowState(int kind, String label) {
            this.kind = kind;
            this.label = label;
        }
    }

    /** The ask on screen: what was said, what it meant, its matches, and the narrowing switched on. */
    private static final class AskState {
        final String words;
        Ask.Meaning meaning;
        boolean thinking = true, byTitle;
        final List<TitleSearch.Title> all = new ArrayList<>();
        final List<Narrow> narrows = new ArrayList<>();
        final Set<Integer> on = new HashSet<>();
        String note = "";

        AskState(String words) {
            this.words = words;
        }
    }

    /** One of the ways to narrow an ask down. */
    private static final class Narrow {
        final String label;
        final java.util.function.Predicate<TitleSearch.Title> keep;

        Narrow(String label, java.util.function.Predicate<TitleSearch.Title> keep) {
            this.label = label;
            this.keep = keep;
        }
    }

    /** A title full screen: where to watch it (detail), or One pick going through a list. */
    private static final class Over {
        final boolean pick;
        final List<TitleSearch.Title> list;
        final List<String> why;
        int i, f;
        final List<View> buttons = new ArrayList<>();

        Over(boolean pick, List<TitleSearch.Title> list, List<String> why, int i) {
            this.pick = pick;
            this.list = list;
            this.why = why;
            this.i = i;
        }
    }

    SearchScreen(Context ctx, Host host, String country, String apiKey, String leaveOut) {
        this.ctx = ctx;
        this.host = host;
        this.country = country;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.leaveOut = leaveOut == null ? "" : leaveOut;
        this.pm = ctx.getPackageManager();
        this.unit = ctx.getResources().getDisplayMetrics().heightPixels / 1080f;
        this.voice = new Voice(ctx);
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        view = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (typing) return super.dispatchKeyEvent(e);
                return key(e) || super.dispatchKeyEvent(e);
            }
        };
        view.setVisibility(View.GONE);
        view.setBackgroundColor(BG);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setClickable(true);
        // Each part stays in its own box: the rows scroll under the billboard, not over it.
        view.setClipChildren(true);

        // The billboard: a still from whatever has focus, right of the words, fading into the background.
        backdrop = new ImageView(ctx);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.addView(backdrop, new FrameLayout.LayoutParams(px(1500), px(844), Gravity.TOP | Gravity.END));
        View scrim = new View(ctx);
        scrim.setBackground(new Scrim(BG, 0.22f, 0.5f, 0.75f, 0.45f));
        view.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(844), Gravity.TOP));

        // The top bar: the ask bar, One pick, the way home, and the time.
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setClipChildren(false);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(72), Gravity.TOP);
        tlp.leftMargin = tlp.rightMargin = px(96);
        tlp.topMargin = px(44);
        view.addView(top, tlp);
        askBar = new FrameLayout(ctx);
        askBar.setClipChildren(false);
        LinearLayout askRow = new LinearLayout(ctx);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        askRow.setPadding(px(12), 0, px(30), 0);
        askRow.setClipChildren(false);
        askBar.addView(askRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout micBox = new FrameLayout(ctx);
        micBox.setClipChildren(false);
        askPulse = new Voice.Pulse(px(24), px(8), px(2));
        View ring = new View(ctx);
        ring.setBackground(askPulse);
        micBox.addView(ring, new FrameLayout.LayoutParams(px(90), px(90), Gravity.CENTER));
        askMic = new FrameLayout(ctx);
        askMicIcon = new ImageView(ctx);
        askMicIcon.setImageResource(R.drawable.ic_mic);
        askMic.addView(askMicIcon, new FrameLayout.LayoutParams(px(24), px(24), Gravity.CENTER));
        micBox.addView(askMic, new FrameLayout.LayoutParams(px(48), px(48), Gravity.CENTER));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(px(48), px(48));
        mlp.rightMargin = px(16);
        askRow.addView(micBox, mlp);
        FrameLayout textBox = new FrameLayout(ctx);
        askRow.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        askText = label(R.string.font_regular, 0xBFFFFFFF, 24);
        askText.setEllipsize(TextUtils.TruncateAt.START);
        textBox.addView(askText);
        askField = new EditText(ctx);
        askField.setTypeface(UiFont.get(ctx, R.string.font_regular));
        askField.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(24));
        askField.setTextColor(0xFFFFFFFF);
        askField.setHintTextColor(FAINT);
        askField.setHint(R.string.search_ask_bar);
        askField.setSingleLine(true);
        askField.setBackground(null);
        askField.setPadding(0, 0, 0, 0);
        askField.setVisibility(View.GONE);
        askField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        askField.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                String q = askField.getText().toString().trim();
                stopTyping();
                if (!q.isEmpty()) ask(q);
            }
            return go;
        });
        textBox.addView(askField, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        TextView okHint = label(R.string.font_medium, 0x8CFFFFFF, 15);
        okHint.setText(R.string.search_pill_hint);
        askRow.addView(okHint);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, px(72), 1f);
        top.addView(askBar, alp);
        onePick = label(R.string.font_regular, 0xFFFFFFFF, 22);
        SpannableStringBuilder op = new SpannableStringBuilder("✦  ").append(ctx.getString(R.string.search_one_pick));
        op.setSpan(new ForegroundColorSpan(IMDB), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        onePick.setText(op);
        onePick.setGravity(Gravity.CENTER_VERTICAL);
        onePick.setPadding(px(22), 0, px(26), 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(72));
        plp.leftMargin = px(24);
        top.addView(onePick, plp);
        TextView backHint = label(R.string.font_regular, FAINT, 19);
        backHint.setText(R.string.search_back_home);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = px(36);
        top.addView(backHint, blp);
        TextClock clock = new TextClock(ctx);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTypeface(UiFont.get(ctx, R.string.font_extralight));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(56));
        clock.setTextColor(0xFFFFFFFF);
        clock.setIncludeFontPadding(false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = px(32);
        top.addView(clock, clp);

        // What has focus: the row it's from, its name, the facts, a line about it, and where it's on.
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        ilp.leftMargin = px(96);
        ilp.topMargin = px(190);
        view.addView(info, ilp);
        infoCaps = caps(17);
        info.addView(infoCaps);
        infoTitle = label(R.string.font_medium, 0xFFFFFFFF, 76);
        infoTitle.setLetterSpacing(-0.02f);
        info.addView(infoTitle, top(14));
        infoMeta = label(R.string.font_regular, MUTED, 22);
        info.addView(infoMeta, top(16));
        infoDesc = label(R.string.font_regular, 0xD6FFFFFF, 23);
        infoDesc.setSingleLine(false);
        infoDesc.setMaxLines(2);
        infoDesc.setLineSpacing(0, 1.4f);
        info.addView(infoDesc, top(14));
        infoOn = label(R.string.font_regular, MUTED, 19);
        info.addView(infoOn, top(20));

        // The rows, from 540px down; the focused one slides to the top of this box. A scroll view so the rows below
        // the screen's edge are laid out at full height and clipped at the box's top, under the billboard.
        rowsBox = new android.widget.ScrollView(ctx);
        rowsBox.setVerticalScrollBarEnabled(false);
        rowsBox.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowsBox.setFocusable(false);
        rowsBox.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP);
        rlp.topMargin = px(540);
        view.addView(rowsBox, rlp);
        track = new LinearLayout(ctx);
        track.setOrientation(LinearLayout.VERTICAL);
        track.setClipChildren(false);
        rowsBox.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        over = new FrameLayout(ctx);
        over.setBackgroundColor(BG);
        over.setVisibility(View.GONE);
        view.addView(over, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintTop();
    }

    boolean isOpen() {
        return view.getVisibility() == View.VISIBLE;
    }

    /** Opens on what was said, or to browse when null. */
    void open(String said) {
        seq++;
        ask = null;
        overState = null;
        over.setVisibility(View.GONE);
        stopTyping();
        stopListening();
        r = 0;
        topF = 0;
        view.setVisibility(View.VISIBLE);
        view.setAlpha(0f);
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start();
        view.requestFocus();
        buildRows();
        if (said != null && !said.trim().isEmpty()) ask(said.trim());
        else render();
    }

    void close() {
        if (!isOpen()) return;
        seq++;
        stopListening();
        voice.release();
        stopTyping();
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            view.setVisibility(View.GONE);
            over.removeAllViews();
            track.removeAllViews();
            rows.clear();
            backdrops.evictAll();
        }).start();
        host.onClosed();
    }

    /** Back: out of typing, a title, an ask, the rows, then home. */
    boolean back() {
        if (!isOpen()) return false;
        if (typing) {
            stopTyping();
        } else if (listening) {
            stopListening();
        } else if (overState != null) {
            closeOver();
        } else if (ask != null) {
            ask = null;
            seq++;
            buildRows();
            r = 0;
            render();
        } else if (r >= 0) {
            r = -1;
            topF = 0;
            render();
        } else {
            close();
        }
        return true;
    }

    void micAnswered(boolean granted) {
        if (isOpen() && granted) listen();
    }

    // ---- rows ------------------------------------------------------------------------------------

    /** Continue watching, the ask (when there is one), the pinned asks, then the standard rows; each loads itself. */
    private void buildRows() {
        rows.clear();
        track.removeAllViews();
        if (ask != null) {
            rows.add(new RowState(CHIPS, ""));
            rows.add(new RowState(ASK, ask.words));
        }
        RowState cont = new RowState(CONT, ctx.getString(R.string.search_row_continue));
        for (NowPlaying.Item it : NowPlaying.load(ctx)) {
            if (it.pkg.equals(leaveOut) || it.title.isEmpty() || pm.getLeanbackLaunchIntentForPackage(it.pkg) == null) continue;
            // The TV's own inputs (HDMI 1) report what they play too; they aren't something to resume.
            if (it.pkg.startsWith("com.sony.dtv") || it.pkg.equals("com.google.android.tv")) continue;
            cont.cont.add(it);
        }
        cont.loading = false;
        if (!cont.cont.isEmpty()) rows.add(cont);
        List<Discover.Row> pinned = pinnedRows();
        for (int i = 0; i < pinned.size(); i++) {
            RowState s = new RowState(ROW, pinned.get(i).label);
            s.def = pinned.get(i);
            s.pinned = true;
            s.pinIndex = i;
            rows.add(s);
        }
        for (Discover.Row d : Discover.standardRows(ctx, pm)) {
            RowState s = new RowState(ROW, d.label);
            s.def = d;
            rows.add(s);
        }
        for (RowState s : rows) {
            track.addView(rowView(s));
            if (s.kind == ROW) load(s);
        }
        if (ask != null) fillAsk();
    }

    private void load(RowState s) {
        int mine = seq;
        List<String> services = Discover.installedServices(pm);
        worker.execute(() -> {
            List<TitleSearch.Title> found = null;
            try {
                found = TitleSearch.query(s.def.filter.toJustWatch(services, pm), s.def.filter.sort, country, 24, pm);
            } catch (Exception ignored) {
            }
            List<TitleSearch.Title> answer = found;
            main.post(() -> {
                if (mine != seq) return;
                s.loading = false;
                s.items.clear();
                if (answer != null) for (TitleSearch.Title t : answer) if (!t.offers.isEmpty() && !isSeen(t)) s.items.add(t);
                fillStrip(s);
                if (rows.indexOf(s) == r) render();
            });
        });
    }

    /** A row: its heading (with ✦ Your ask on a pinned one) and the strip of cards. */
    private View rowView(RowState s) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setPadding(px(96), 0, 0, px(10));
        row.setClipToPadding(false);
        if (s.kind == CHIPS) {
            s.strip = new LinearLayout(ctx);
            s.strip.setOrientation(LinearLayout.VERTICAL);
            s.strip.setClipChildren(false);
            row.addView(s.strip);
            return row;
        }
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = s.kind == ASK ? caps(15) : label(R.string.font_medium, 0xFFFFFFFF, 26);
        if (s.kind == ASK) name.setTextColor(IMDB);
        name.setText(s.kind == ASK ? ctx.getString(R.string.search_your_ask) : s.label);
        head.addView(name);
        if (s.pinned) {
            TextView tag = label(R.string.font_regular, 0xDBFFFFFF, 15);
            tag.setText(R.string.search_pinned_tag);
            tag.setPadding(px(12), px(4), px(12), px(4));
            tag.setBackground(pill(0x0DFFFFFF, LINE, px(16)));
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = px(14);
            head.addView(tag, lp);
        }
        s.count = label(R.string.font_regular, FAINT, 20);
        LinearLayout.LayoutParams clp = wrap();
        clp.leftMargin = px(14);
        head.addView(s.count, clp);
        row.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));
        s.strip = new LinearLayout(ctx);
        s.strip.setOrientation(LinearLayout.HORIZONTAL);
        s.strip.setClipChildren(false);
        s.strip.setPadding(0, px(18), 0, px(22));
        s.strip.setClipToPadding(false);
        row.addView(s.strip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fillStrip(s);
        return row;
    }

    /** The cards of a row: placeholders while it loads, then a card per title (or per Continue item). */
    private void fillStrip(RowState s) {
        if (s.kind == CHIPS) return;
        s.strip.removeAllViews();
        s.cells.clear();
        if (s.kind == CONT) {
            for (NowPlaying.Item it : s.cont) add(s, landCard(it));
            return;
        }
        if (s.loading) {
            for (int i = 0; i < 7; i++) {
                View ph = new View(ctx);
                ph.setBackground(rounded(0x12FFFFFF, px(16)));
                s.strip.addView(ph, cell(POSTER_W, POSTER_H));
            }
            if (s.count != null) s.count.setText("");
            return;
        }
        List<TitleSearch.Title> shown = s.kind == ASK ? narrowed() : s.items;
        if (s.count != null) {
            s.count.setText(s.kind == ASK && !ask.thinking ? ctx.getString(R.string.search_matches, shown.size()) : "");
        }
        if (shown.isEmpty()) {
            TextView none = label(R.string.font_regular, MUTED, 22);
            none.setText(s.kind == ASK && ask != null && !ask.on.isEmpty() ? ctx.getString(R.string.search_nothing_left)
                    : s.kind == ASK && ask != null && !ask.note.isEmpty() ? ask.note
                    : ctx.getString(R.string.search_nothing_found));
            s.strip.addView(none, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(POSTER_H)));
            none.setGravity(Gravity.CENTER_VERTICAL);
        }
        for (TitleSearch.Title t : shown) add(s, posterCard(t));
        if (s.pinned) add(s, removeCard(s));
        s.col = Math.min(s.col, Math.max(0, s.cells.size() - 1));
    }

    private void add(RowState s, View cellView) {
        boolean land = s.kind == CONT;
        s.strip.addView(cellView, cell(land ? LAND_W : POSTER_W, land ? LAND_H : POSTER_H));
        s.cells.add(cellView);
    }

    /** A card: the outer frame takes the focus ring and the dim; the inside is rounded and clips its picture. */
    private FrameLayout[] cardFrame(float radius) {
        FrameLayout outer = new FrameLayout(ctx);
        FrameLayout inner = new FrameLayout(ctx);
        roundClip(inner, radius);
        inner.setBackgroundColor(CARD);
        outer.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return new FrameLayout[]{outer, inner};
    }

    private View posterCard(TitleSearch.Title t) {
        FrameLayout[] frame = cardFrame(px(16));
        FrameLayout card = frame[1];
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPoster(t.poster, img);
        // Along the bottom: "Netflix +1 | ★ 8.7", over a fade so it reads on any poster.
        TextView foot = label(R.string.font_medium, 0xFFFFFFFF, 17);
        foot.setText(foot(t));
        foot.setPadding(px(14), px(46), px(14), px(12));
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x99000000, 0x00000000});
        foot.setBackground(fade);
        card.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        frame[0].setForeground(dim(px(16)));
        return frame[0];
    }

    private CharSequence foot(TitleSearch.Title t) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!t.offers.isEmpty()) {
            b.append(Discover.shortLabel(Discover.appLabel(pm, t.offers.get(0).pkg)));
            if (t.offers.size() > 1) {
                int s = b.length();
                b.append(" +").append(String.valueOf(t.offers.size() - 1));
                b.setSpan(new ForegroundColorSpan(MUTED), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (t.imdb > 0) {
            int s = b.length();
            b.append(b.length() > 0 ? "  |  " : "");
            b.setSpan(new ForegroundColorSpan(0x59FFFFFF), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s = b.length();
            b.append("★");
            b.setSpan(new ForegroundColorSpan(IMDB), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(' ').append(score(t.imdb));
        }
        return b;
    }

    private View landCard(NowPlaying.Item it) {
        FrameLayout[] frame = cardFrame(px(18));
        FrameLayout card = frame[1];
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        File art = NowPlaying.artFile(ctx, it.pkg);
        if (art.exists()) {
            worker.execute(() -> {
                Bitmap b = BitmapFactory.decodeFile(art.getPath());
                if (b != null) main.post(() -> img.setImageBitmap(b));
            });
        } else {
            // No artwork from the app: its icon in the middle of the card.
            try {
                img.setImageDrawable(pm.getApplicationIcon(it.pkg));
                img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                img.setPadding(px(150), px(50), px(150), px(90));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        View fade = new View(ctx);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        card.addView(fade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(px(20), 0, px(20), px(16));
        TextView name = label(R.string.font_medium, 0xFFFFFFFF, 24);
        name.setText(it.title);
        text.addView(name);
        TextView sub = label(R.string.font_regular, MUTED, 16);
        sub.setText(it.subtitle.isEmpty() ? Discover.appLabel(pm, it.pkg) : it.subtitle + " · " + Discover.appLabel(pm, it.pkg));
        text.addView(sub, top(4));
        card.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        if (it.duration > 0) {
            View bar = new View(ctx);
            bar.setBackgroundColor(0xFFFFFFFF);
            int w = Math.round(px(LAND_W) * Math.min(1f, it.positionNow() / (float) it.duration));
            card.addView(bar, new FrameLayout.LayoutParams(w, px(5), Gravity.BOTTOM | Gravity.START));
        }
        frame[0].setForeground(dim(px(18)));
        return frame[0];
    }

    private static Drawable dim(float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x33000000);
        d.setCornerRadius(r);
        return d;
    }

    /** At the end of a pinned row: take the row off the screen. */
    private View removeCard(RowState s) {
        TextView t = label(R.string.font_regular, MUTED, 21);
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        t.setText(R.string.search_unpin);
        GradientDrawable d = rounded(0, px(16));
        d.setStroke(px(2), 0x38FFFFFF, px(10), px(8));
        t.setBackground(d);
        return t;
    }

    // ---- asking ----------------------------------------------------------------------------------

    /** Asks: the chips line and an empty shelf say it's thinking, then what it understood and the matches. */
    private void ask(String words) {
        stopListening();
        overState = null;
        over.setVisibility(View.GONE);
        ask = new AskState(words);
        seq++;
        int mine = seq;
        buildRows();
        r = 1;
        render();
        List<String> services = Discover.installedServices(pm);
        worker.execute(() -> {
            AskState a = ask;
            Ask.Meaning meaning = null;
            List<TitleSearch.Title> found = new ArrayList<>();
            boolean byTitle = apiKey.isEmpty();
            String note = "";
            try {
                if (!byTitle) {
                    meaning = Ask.understand(words, apiKey);
                    if (!meaning.title.isEmpty()) found = matching(TitleSearch.search(meaning.title, country, pm), meaning.title);
                    else if (!meaning.titles.isEmpty()) found = lookUp(meaning.titles);
                    else found = TitleSearch.query(meaning.filter.toJustWatch(services, pm), meaning.filter.sort, country, 40, pm);
                }
            } catch (Exception e) {
                // Claude couldn't be reached or didn't answer: look the words up as a title instead.
                byTitle = true;
                meaning = null;
            }
            if (byTitle) {
                try {
                    found = matching(TitleSearch.search(words, country, pm), words);
                    if (apiKey.isEmpty()) note = ctx.getString(R.string.search_no_key);
                } catch (Exception e) {
                    note = ctx.getString(R.string.search_offline_rows);
                }
            }
            Ask.Meaning m = meaning;
            boolean title = byTitle;
            List<TitleSearch.Title> answer = found;
            String why = note;
            main.post(() -> {
                if (mine != seq || ask != a) return;
                a.meaning = m;
                a.byTitle = title || (m != null && !m.title.isEmpty());
                a.thinking = false;
                a.note = why;
                for (TitleSearch.Title t : answer) if (!t.offers.isEmpty() && !isSeen(t)) a.all.add(t);
                a.narrows.addAll(narrowsFor(a.all));
                remember(words);
                fillAsk();
                render();
            });
        });
    }

    /** The title asked for first, then the others the catalogue found; only what some app here has. */
    private static List<TitleSearch.Title> matching(List<TitleSearch.Title> found, String words) {
        List<TitleSearch.Title> out = new ArrayList<>(found);
        for (int i = 0; i < out.size(); i++) {
            if (TitleSearch.matches(out.get(i).title, words)) {
                out.add(0, out.remove(i));
                break;
            }
        }
        return out;
    }

    /** Each title Claude named, looked up at once; the best catalogue match for each, in Claude's order. */
    private List<TitleSearch.Title> lookUp(List<String> titles) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<TitleSearch.Title>>> futures = new ArrayList<>();
            for (String t : titles) futures.add(pool.submit(() -> TitleSearch.search(t, country, pm)));
            Map<String, TitleSearch.Title> out = new LinkedHashMap<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    for (TitleSearch.Title t : futures.get(i).get()) {
                        if (TitleSearch.matches(t.title, titles.get(i)) && !t.offers.isEmpty()) {
                            out.putIfAbsent(t.id.isEmpty() ? t.title : t.id, t);
                            break;
                        }
                    }
                } catch (java.util.concurrent.ExecutionException ignored) {
                }
            }
            return new ArrayList<>(out.values());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Ways to narrow the matches that would each change something: shorter, newer, and one app or another. */
    private List<Narrow> narrowsFor(List<TitleSearch.Title> all) {
        List<Narrow> out = new ArrayList<>();
        if (all.size() < 3) return out;
        int films = 0, old = 0;
        Map<String, Integer> apps = new LinkedHashMap<>();
        for (TitleSearch.Title t : all) {
            if ("MOVIE".equals(t.kind)) films++;
            if (t.year > 0 && t.year < 2010) old++;
            for (TitleSearch.Offer o : t.offers) apps.merge(o.pkg, 1, Integer::sum);
        }
        if (films > 1) out.add(new Narrow(ctx.getString(R.string.search_narrow_short), t -> minutes(t) <= 120));
        if (old > 0 && old < all.size()) out.add(new Narrow(ctx.getString(R.string.search_narrow_newer), t -> t.year >= 2010));
        List<String> byCount = new ArrayList<>(apps.keySet());
        byCount.sort((a, b) -> apps.get(b) - apps.get(a));
        for (int i = 0; i < Math.min(2, byCount.size()) && byCount.size() > 1; i++) {
            String pkg = byCount.get(i);
            String name = Discover.shortLabel(Discover.appLabel(pm, pkg));
            out.add(new Narrow(ctx.getString(R.string.search_narrow_only, name), t -> {
                for (TitleSearch.Offer o : t.offers) if (o.pkg.equals(pkg)) return true;
                return false;
            }));
        }
        return out;
    }

    private static int minutes(TitleSearch.Title t) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:(\\d+)h )?(\\d+)m").matcher(t.length);
        if (!m.matches()) return 999;
        return (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 60) + Integer.parseInt(m.group(2));
    }

    private List<TitleSearch.Title> narrowed() {
        List<TitleSearch.Title> out = new ArrayList<>();
        if (ask == null) return out;
        for (TitleSearch.Title t : ask.all) {
            boolean keep = !isSeen(t);
            for (int i : ask.on) keep &= ask.narrows.get(i).keep.test(t);
            if (keep) out.add(t);
        }
        return out;
    }

    /** The chips line (what it understood; ways to narrow it; pin) and the ask's shelf. */
    private void fillAsk() {
        if (ask == null || rows.size() < 2) return;
        RowState chips = rows.get(0), shelf = rows.get(1);
        chips.strip.removeAllViews();
        chips.cells.clear();
        LinearLayout line1 = chipLine(ask.thinking ? R.string.search_understanding : R.string.search_understood);
        chips.strip.addView(line1);
        if (ask.thinking) {
            for (int i = 0; i < 4; i++) {
                View ph = new View(ctx);
                ph.setBackground(pill(0x12FFFFFF, 0, px(22)));
                line1.addView(ph, chipGap(px(120), px(44)));
            }
        } else {
            List<String> tags = new ArrayList<>();
            if (ask.meaning != null && !ask.byTitle) tags.addAll(ask.meaning.tags);
            if (tags.isEmpty()) tags.add("“" + ask.words + "”");
            for (String t : tags) line1.addView(chip(t, true, false), chipGap(ViewGroup.LayoutParams.WRAP_CONTENT, px(44)));
            LinearLayout line2 = chipLine(R.string.search_narrow);
            for (int i = 0; i < ask.narrows.size(); i++) {
                View c = chip("+  " + ask.narrows.get(i).label, ask.on.contains(i), true);
                line2.addView(c, chipGap(ViewGroup.LayoutParams.WRAP_CONTENT, px(44)));
                chips.cells.add(c);
            }
            if (!ask.byTitle && ask.meaning != null) {
                View pin = chip(ctx.getString(R.string.search_pin), false, true);
                LinearLayout.LayoutParams lp = chipGap(ViewGroup.LayoutParams.WRAP_CONTENT, px(44));
                lp.leftMargin = px(12);
                line2.addView(pin, lp);
                chips.cells.add(pin);
            }
            if (!chips.cells.isEmpty()) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = px(10);
                chips.strip.addView(line2, lp);
            }
        }
        chips.loading = false;
        shelf.loading = ask.thinking;
        fillStrip(shelf);
    }

    private LinearLayout chipLine(int head) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setClipChildren(false);
        TextView h = caps(15);
        h.setText(head);
        line.addView(h, new LinearLayout.LayoutParams(px(190), ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private TextView chip(String text, boolean on, boolean focusable) {
        TextView c = label(R.string.font_regular, on ? 0xFFFFFFFF : 0xDBFFFFFF, 19);
        c.setText(text);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(18), 0, px(18), 0);
        c.setBackground(pill(on ? 0x29FFFFFF : 0x0DFFFFFF, on ? 0x4DFFFFFF : LINE, px(22)));
        c.setTag(on);
        return c;
    }

    private LinearLayout.LayoutParams chipGap(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.rightMargin = px(12);
        return lp;
    }

    /** OK on a chip: switch a narrowing on or off, or pin the ask as a row. */
    private void chipPressed(int index) {
        if (ask == null) return;
        if (index < ask.narrows.size()) {
            if (!ask.on.remove(index)) ask.on.add(index);
            fillAsk();
            render();
            return;
        }
        pin();
    }

    private void pin() {
        if (ask == null || ask.meaning == null) return;
        String label = ask.meaning.label.isEmpty() ? ask.words : ask.meaning.label;
        try {
            JSONArray list = new JSONArray(prefs.getString(PINNED, "[]"));
            JSONArray out = new JSONArray().put(new JSONObject().put("label", label).put("filter", ask.meaning.filter.toJson()));
            for (int i = 0; i < list.length() && out.length() < 8; i++) {
                if (!label.equalsIgnoreCase(list.getJSONObject(i).optString("label"))) out.put(list.get(i));
            }
            prefs.edit().putString(PINNED, out.toString()).apply();
        } catch (JSONException ignored) {
            return;
        }
        toast(ctx.getString(R.string.search_pinned));
        ask = null;
        seq++;
        buildRows();
        r = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).pinned) {
            r = i;
            break;
        }
        render();
    }

    private List<Discover.Row> pinnedRows() {
        List<Discover.Row> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs.getString(PINNED, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                out.add(new Discover.Row(o.optString("label"), Discover.Filter.fromJson(o.optJSONObject("filter") == null ? new JSONObject() : o.getJSONObject("filter")), true));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private void unpin(RowState s) {
        try {
            JSONArray list = new JSONArray(prefs.getString(PINNED, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < list.length(); i++) if (i != s.pinIndex) out.put(list.get(i));
            prefs.edit().putString(PINNED, out.toString()).apply();
        } catch (JSONException ignored) {
            return;
        }
        int at = rows.indexOf(s);
        seq++;
        buildRows();
        r = Math.max(0, Math.min(at, rows.size() - 1));
        render();
    }

    private void remember(String q) {
        // Kept for the old recent-searches list: the newest four.
        String s = prefs.getString("recent", "");
        List<String> list = new ArrayList<>();
        list.add(q);
        for (String x : s.isEmpty() ? new String[0] : s.split("\n")) if (!x.equalsIgnoreCase(q) && list.size() < 4) list.add(x);
        prefs.edit().putString("recent", TextUtils.join("\n", list)).apply();
    }

    // ---- listening in the ask bar ----------------------------------------------------------------

    private void listen() {
        if (!voice.available()) {
            startTyping();
            return;
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestMic();
            return;
        }
        listening = true;
        askText.setText(R.string.search_say);
        askText.setTextColor(FAINT);
        paintTop();
        voice.listen(new Voice.Ear() {
            @Override
            public void onLevel(float level) { }

            @Override
            public void onPartial(String text) {
                askText.setText(text);
                askText.setTextColor(0xFFFFFFFF);
            }

            @Override
            public void onHeard(String text) {
                listening = false;
                paintTop();
                if (text != null && !text.trim().isEmpty()) ask(text.trim());
                else render();
            }

            @Override
            public void onNeedMic() {
                listening = false;
                paintTop();
                host.requestMic();
            }
        });
    }

    private void stopListening() {
        if (!listening) return;
        listening = false;
        voice.cancel();
        paintTop();
        render();
    }

    /** Holding OK on the ask bar: type instead. */
    private void startTyping() {
        typing = true;
        askText.setVisibility(View.INVISIBLE);
        askField.setVisibility(View.VISIBLE);
        askField.setText("");
        askField.setFocusable(true);
        askField.setFocusableInTouchMode(true);
        askField.requestFocus();
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) askField.post(() -> imm.showSoftInput(askField, InputMethodManager.SHOW_IMPLICIT));
    }

    private void stopTyping() {
        if (!typing) return;
        typing = false;
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(askField.getWindowToken(), 0);
        askField.setVisibility(View.GONE);
        askText.setVisibility(View.VISIBLE);
        view.requestFocus();
    }

    // ---- keys ------------------------------------------------------------------------------------

    private boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean arrow = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ok && !arrow) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) return true;
        // Held OK on the ask bar types instead of listening.
        if (ok && e.getRepeatCount() > 0) {
            if (e.getRepeatCount() == 1 && overState == null && r < 0 && topF == 0) {
                stopListening();
                startTyping();
            }
            return true;
        }
        if (listening) {
            stopListening();
            if (ok) return true;
        }
        if (overState != null) return overKey(code, ok);
        if (ask != null && ask.thinking && !ok && r >= 0) {
            // Rows are still moving under the answer; Up still reaches the bar.
            if (code != KeyEvent.KEYCODE_DPAD_UP) return true;
        }
        if (r < 0) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) topF = 0;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) topF = 1;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN && !rows.isEmpty()) r = 0;
            else if (ok) {
                if (topF == 0) listen();
                else onePick();
                return true;
            }
            render();
            return true;
        }
        RowState s = rows.get(r);
        int n = s.cells.size();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                r--;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                r = Math.min(rows.size() - 1, r + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                s.col = Math.max(0, s.col - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                s.col = Math.min(Math.max(0, n - 1), s.col + 1);
                break;
            default:
                pressed(s);
                return true;
        }
        render();
        return true;
    }

    private void pressed(RowState s) {
        if (s.cells.isEmpty()) return;
        if (s.kind == CHIPS) {
            chipPressed(s.col);
            return;
        }
        if (s.kind == CONT) {
            NowPlaying.Item it = s.cont.get(s.col);
            Intent i = pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(it.pkg);
            if (i != null) start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
            return;
        }
        List<TitleSearch.Title> list = s.kind == ASK ? narrowed() : s.items;
        if (s.pinned && s.col >= list.size()) {
            unpin(s);
            return;
        }
        if (s.col < list.size()) openOver(false, new ArrayList<>(list), null, s.col);
    }

    // ---- drawing focus and the billboard ---------------------------------------------------------

    private void render() {
        paintTop();
        for (int i = 0; i < rows.size(); i++) {
            RowState s = rows.get(i);
            for (int c = 0; c < s.cells.size(); c++) focus(s.cells.get(c), i == r && c == s.col, s.kind == CHIPS);
        }
        if (r >= rows.size()) r = rows.size() - 1;
        // The focused row (or, on the ask's shelf, its chips line above it) at the top of the rows box.
        if (r >= 0 && r < track.getChildCount()) {
            int anchor = rows.get(r).kind == ASK && r > 0 && rows.get(r - 1).kind == CHIPS ? r - 1 : r;
            View target = track.getChildAt(anchor);
            Runnable scroll = () -> rowsBox.smoothScrollTo(0, target.getTop());
            if (target.getHeight() == 0) target.post(scroll);
            else scroll.run();
            RowState s = rows.get(r);
            if (s.kind != CHIPS && !s.cells.isEmpty()) {
                int w = px((s.kind == CONT ? LAND_W : POSTER_W) + GAP);
                s.strip.animate().translationX(-Math.max(0, s.col - 3) * w).setDuration(380).start();
            }
        } else {
            rowsBox.smoothScrollTo(0, 0);
        }
        billboard();
    }

    private void focus(View v, boolean on, boolean chip) {
        if (chip) {
            boolean active = Boolean.TRUE.equals(v.getTag());
            v.setBackground(on ? pill(0xFFFFFFFF, 0, px(22)) : pill(active ? 0x29FFFFFF : 0x0DFFFFFF, active ? 0x4DFFFFFF : LINE, px(22)));
            if (v instanceof TextView) ((TextView) v).setTextColor(on ? INK : 0xFFFFFFFF);
            v.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(200).start();
            return;
        }
        if (v instanceof FrameLayout) {
            v.setForeground(on ? ring(px(16)) : dim(px(16)));
        } else {
            v.setAlpha(on ? 1f : 0.7f);
        }
        v.setElevation(on ? px(12) : 0);
        v.animate().scaleX(on ? 1.07f : 1f).scaleY(on ? 1.07f : 1f).setDuration(240).start();
    }

    private void paintTop() {
        boolean barOn = r < 0 && topF == 0 && overState == null;
        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x24FFFFFF, 0x0FFFFFFF});
        bar.setCornerRadius(px(36));
        bar.setStroke(Math.max(1, px(1.5f)), barOn || listening ? 0x99FFFFFF : 0x2EFFFFFF);
        askBar.setBackground(bar);
        GradientDrawable mic = new GradientDrawable();
        mic.setShape(GradientDrawable.OVAL);
        mic.setColor(listening ? 0xFFFFFFFF : 0x1FFFFFFF);
        askMic.setBackground(mic);
        askMicIcon.setColorFilter(listening ? INK : 0xFFFFFFFF);
        askPulse.setRunning(listening);
        if (!listening && !typing) {
            askText.setText(ask != null ? ask.words : ctx.getString(R.string.search_ask_bar));
            askText.setTextColor(ask != null || barOn ? 0xFFFFFFFF : 0xBFFFFFFF);
        }
        boolean pickOn = r < 0 && topF == 1 && overState == null;
        onePick.setBackground(pill(pickOn ? 0xFFFFFFFF : 0x1FFFFFFF, pickOn ? 0 : 0x38FFFFFF, px(36)));
        onePick.setTextColor(pickOn ? INK : 0xFFFFFFFF);
    }

    /** The billboard for whatever has focus; on the bar or the chips, a line about the screen or the ask. */
    private void billboard() {
        if (r < 0 || r >= rows.size() || rows.get(r).kind == CHIPS || rows.get(r).cells.isEmpty()) {
            boolean asked = ask != null && !ask.thinking;
            infoCaps.setText(asked ? ctx.getString(R.string.search_your_ask) : ctx.getString(R.string.search_pill));
            infoTitle.setText(asked ? ask.words : ctx.getString(R.string.search_idle_title));
            infoMeta.setText(asked ? ctx.getString(R.string.search_matches, narrowed().size()) : ctx.getString(R.string.search_idle_line));
            infoDesc.setText("");
            infoOn.setText("");
            setBackdrop("", null);
            return;
        }
        RowState s = rows.get(r);
        if (s.kind == CONT) {
            NowPlaying.Item it = s.cont.get(Math.min(s.col, s.cont.size() - 1));
            infoCaps.setText(s.label);
            infoTitle.setText(it.title);
            infoMeta.setText(it.subtitle);
            infoDesc.setText("");
            infoOn.setText(ctx.getString(R.string.search_resume, Discover.appLabel(pm, it.pkg)));
            File art = NowPlaying.artFile(ctx, it.pkg);
            setBackdrop(art.exists() ? art.getPath() : "", art.exists() ? art : null);
            return;
        }
        List<TitleSearch.Title> list = s.kind == ASK ? narrowed() : s.items;
        if (s.col >= list.size()) {
            infoCaps.setText(s.label);
            infoTitle.setText(R.string.search_unpin);
            infoMeta.setText("");
            infoDesc.setText("");
            infoOn.setText("");
            setBackdrop("", null);
            return;
        }
        TitleSearch.Title t = list.get(s.col);
        SpannableStringBuilder caps = new SpannableStringBuilder();
        if (s.pinned) caps.append("✦ ");
        caps.append(s.kind == ASK ? ctx.getString(R.string.search_your_ask) : s.label);
        infoCaps.setText(caps);
        infoTitle.setText(t.title);
        infoMeta.setText(meta(t));
        infoDesc.setText(t.description);
        infoOn.setText(ctx.getString(R.string.search_on, apps(t)));
        setBackdrop(t.backdrop.isEmpty() ? t.poster : t.backdrop, null);
    }

    private CharSequence meta(TitleSearch.Title t) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (t.year > 0) b.append(String.valueOf(t.year)).append(" · ");
        b.append(ctx.getString("MOVIE".equals(t.kind) ? R.string.search_movie : R.string.search_show));
        if (!t.length.isEmpty()) b.append(" · ").append(t.length);
        if (t.imdb > 0) {
            b.append(" · ");
            int s = b.length();
            b.append("★ ").append(score(t.imdb));
            b.setSpan(new ForegroundColorSpan(IMDB), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append(' ').append(ctx.getString(R.string.search_imdb));
        }
        List<String> names = new ArrayList<>();
        for (String g : t.genres) {
            String name = genreName(g);
            if (name != null && names.size() < 2) names.add(name);
        }
        if (!names.isEmpty()) b.append(" · ").append(TextUtils.join(", ", names));
        return b;
    }

    private String apps(TitleSearch.Title t) {
        List<String> names = new ArrayList<>();
        for (TitleSearch.Offer o : t.offers) names.add(Discover.shortLabel(Discover.appLabel(pm, o.pkg)));
        return TextUtils.join(", ", names);
    }

    private static String genreName(String code) {
        switch (code) {
            case "act": return "Action";
            case "ani": return "Animation";
            case "cmy": return "Comedy";
            case "crm": return "Crime";
            case "doc": return "Documentary";
            case "drm": return "Drama";
            case "fml": return "Family";
            case "fnt": return "Fantasy";
            case "hrr": return "Horror";
            case "hst": return "History";
            case "msc": return "Music";
            case "rly": return "Reality";
            case "rma": return "Romance";
            case "scf": return "Sci-fi";
            case "spt": return "Sport";
            case "trl": return "Thriller";
            case "war": return "War";
            case "wsn": return "Western";
            default: return null;
        }
    }

    /** Fades the billboard's still to a new one: a URL, or a file (Continue watching's artwork). */
    private void setBackdrop(String key, File file) {
        if (key.equals(backdropFor)) return;
        backdropFor = key;
        backdrop.animate().cancel();
        backdrop.animate().alpha(0f).setDuration(160).start();
        if (key.isEmpty()) return;
        Bitmap cached = backdrops.get(key);
        if (cached != null) {
            showBackdrop(key, cached, file != null);
            return;
        }
        int w = px(1500);
        worker.execute(() -> {
            Bitmap b = file != null ? BitmapFactory.decodeFile(file.getPath()) : TitleSearch.poster(key, w);
            if (b == null) return;
            backdrops.put(key, b);
            main.post(() -> showBackdrop(key, b, file != null));
        });
    }

    private void showBackdrop(String key, Bitmap b, boolean dim) {
        if (!key.equals(backdropFor)) return;
        backdrop.setImageBitmap(b);
        // A poster or album art stretched wide is soft: shown fainter than a real still.
        boolean wide = b.getWidth() > b.getHeight() * 1.3f;
        backdrop.animate().cancel();
        backdrop.animate().alpha(dim || !wide ? 0.35f : 1f).setDuration(260).start();
    }

    private void loadPoster(String url, ImageView into) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = posters.get(url);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        int w = px(POSTER_W);
        worker.execute(() -> {
            Bitmap b = TitleSearch.poster(url, w);
            if (b == null) return;
            posters.put(url, b);
            main.post(() -> into.setImageBitmap(b));
        });
    }

    // ---- a title full screen: where to watch it, and One pick ------------------------------------

    private void onePick() {
        List<TitleSearch.Title> list = new ArrayList<>();
        List<String> why = new ArrayList<>();
        Set<String> have = new HashSet<>();
        for (RowState s : rows) {
            if (s.kind == CONT || s.kind == CHIPS) continue;
            boolean mine = s.kind == ASK || s.pinned || (s.def != null && "IMDB_SCORE".equals(s.def.filter.sort));
            if (!mine) continue;
            List<TitleSearch.Title> items = s.kind == ASK ? narrowed() : s.items;
            int taken = 0;
            for (TitleSearch.Title t : items) {
                if (taken >= 4 || t.backdrop.isEmpty() || !have.add(t.id + t.title)) continue;
                list.add(t);
                why.add(s.kind == ASK ? ctx.getString(R.string.search_why_ask, ask.words)
                        : s.pinned ? ctx.getString(R.string.search_why_ask, s.label) : ctx.getString(R.string.search_why_top));
                taken++;
            }
        }
        if (list.isEmpty()) return;
        openOver(true, list, why, 0);
    }

    private void openOver(boolean pick, List<TitleSearch.Title> list, List<String> why, int i) {
        overState = new Over(pick, list, why, i);
        over.setVisibility(View.VISIBLE);
        over.setAlpha(0f);
        over.animate().alpha(1f).setDuration(260).start();
        drawOver();
        paintTop();
    }

    private void closeOver() {
        overState = null;
        over.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            over.setVisibility(View.GONE);
            over.removeAllViews();
        }).start();
        // Seen it may have taken a title out of the rows.
        for (RowState s : rows) {
            if (s.kind == CONT || s.kind == CHIPS) continue;
            s.items.removeIf(this::isSeen);
            fillStrip(s);
        }
        render();
    }

    private void drawOver() {
        Over o = overState;
        TitleSearch.Title t = o.list.get(o.i);
        over.removeAllViews();
        ImageView still = new ImageView(ctx);
        still.setScaleType(ImageView.ScaleType.CENTER_CROP);
        over.addView(still, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        String key = t.backdrop.isEmpty() ? t.poster : t.backdrop;
        Bitmap cached = backdrops.get(key);
        if (cached != null) still.setImageBitmap(cached);
        else {
            int w = px(1920);
            worker.execute(() -> {
                Bitmap b = TitleSearch.poster(key, w);
                if (b == null) return;
                backdrops.put(key, b);
                main.post(() -> {
                    if (overState == o && o.list.get(o.i) == t) still.setImageBitmap(b);
                });
            });
        }
        if (t.backdrop.isEmpty()) still.setAlpha(0.35f);
        View scrim = new View(ctx);
        scrim.setBackground(new Scrim(BG, 0f, 0.4f, 0.75f, 0.4f));
        over.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView back = label(R.string.font_regular, MUTED, 19);
        back.setText(R.string.search_back_home);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        blp.rightMargin = px(96);
        blp.topMargin = px(60);
        over.addView(back, blp);

        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setClipChildren(false);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(1500), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        ilp.leftMargin = px(96);
        ilp.topMargin = px(230);
        over.addView(info, ilp);
        TextView why = label(R.string.font_regular, 0xFFFFFFFF, 21);
        SpannableStringBuilder w = new SpannableStringBuilder(o.pick ? "✦  " : "★  ");
        w.setSpan(new ForegroundColorSpan(IMDB), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        w.append(o.pick ? o.why.get(o.i) : score(t.imdb) + " " + ctx.getString(R.string.search_imdb));
        why.setText(w);
        why.setPadding(px(18), px(10), px(22), px(10));
        why.setBackground(pill(0x1AFFFFFF, LINE, px(26)));
        info.addView(why, wrap());
        TextView title = label(R.string.font_medium, 0xFFFFFFFF, 96);
        title.setLetterSpacing(-0.025f);
        title.setText(t.title);
        info.addView(title, top(26));
        TextView meta = label(R.string.font_regular, MUTED, 24);
        meta.setText(meta(t));
        info.addView(meta, top(20));
        TextView desc = label(R.string.font_regular, 0xD6FFFFFF, 25);
        desc.setSingleLine(false);
        desc.setMaxLines(3);
        desc.setLineSpacing(0, 1.4f);
        desc.setText(t.description);
        info.addView(desc, new LinearLayout.LayoutParams(px(820), ViewGroup.LayoutParams.WRAP_CONTENT) {{
            topMargin = px(16);
        }});
        LinearLayout acts = new LinearLayout(ctx);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setClipChildren(false);
        info.addView(acts, top(36));
        o.buttons.clear();
        for (int k = 0; k < t.offers.size(); k++) {
            View b = appButton(t.offers.get(k), k == 0);
            b.setTag(t.offers.get(k));
            acts.addView(b, buttonGap());
            o.buttons.add(b);
        }
        if (o.pick) {
            View next = plainButton(R.drawable.ic_skip, ctx.getString(R.string.search_next_pick));
            next.setTag("next");
            acts.addView(next, buttonGap());
            o.buttons.add(next);
        }
        View seen = plainButton(R.drawable.ic_eye, ctx.getString(R.string.search_seen_it));
        seen.setTag("seen");
        acts.addView(seen, buttonGap());
        o.buttons.add(seen);

        if (o.pick) {
            TextView count = label(R.string.font_regular, MUTED, 20);
            count.setText(ctx.getString(R.string.search_pick_of, o.i + 1, o.list.size()));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            clp.leftMargin = px(96);
            clp.bottomMargin = px(70);
            over.addView(count, clp);
            LinearLayout next = new LinearLayout(ctx);
            next.setOrientation(LinearLayout.VERTICAL);
            next.setGravity(Gravity.END);
            TextView head = caps(17);
            head.setText(R.string.search_up_next);
            next.addView(head, wrap());
            LinearLayout strip = new LinearLayout(ctx);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = o.i; k < Math.min(o.list.size(), o.i + 6); k++) {
                FrameLayout p = new FrameLayout(ctx);
                roundClip(p, px(12));
                p.setBackgroundColor(CARD);
                ImageView img = new ImageView(ctx);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                p.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                loadPoster(o.list.get(k).poster, img);
                if (k == o.i) {
                    GradientDrawable now = new GradientDrawable();
                    now.setCornerRadius(px(12));
                    now.setStroke(px(3), IMDB);
                    p.setForeground(now);
                } else {
                    p.setAlpha(0.6f);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(120), px(180));
                lp.leftMargin = px(16);
                strip.addView(p, lp);
            }
            LinearLayout.LayoutParams slp = wrap();
            slp.topMargin = px(14);
            next.addView(strip, slp);
            FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            nlp.rightMargin = px(96);
            nlp.bottomMargin = px(70);
            over.addView(next, nlp);
        }
        o.f = Math.min(o.f, o.buttons.size() - 1);
        renderOver();
    }

    private void renderOver() {
        Over o = overState;
        for (int k = 0; k < o.buttons.size(); k++) {
            View b = o.buttons.get(k);
            boolean on = k == o.f;
            b.setForeground(on ? ring(px(22)) : null);
            b.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(200).start();
        }
    }

    private boolean overKey(int code, boolean ok) {
        Over o = overState;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) o.f = Math.max(0, o.f - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) o.f = Math.min(o.buttons.size() - 1, o.f + 1);
        else if (ok) {
            Object tag = o.buttons.get(o.f).getTag();
            TitleSearch.Title t = o.list.get(o.i);
            if ("seen".equals(tag)) {
                markSeen(t);
                toast(ctx.getString(R.string.search_wont_show, t.title));
                if (!o.pick) {
                    closeOver();
                    return true;
                }
                o.list.remove(o.i);
                o.why.remove(o.i);
                if (o.list.isEmpty()) {
                    closeOver();
                    return true;
                }
                if (o.i >= o.list.size()) o.i = 0;
                o.f = 0;
                drawOver();
            } else if ("next".equals(tag)) {
                o.i = (o.i + 1) % o.list.size();
                o.f = 0;
                drawOver();
            } else if (tag instanceof TitleSearch.Offer) {
                markSeen(t);
                Intent i = TitleSearch.intentFor((TitleSearch.Offer) tag, t.title, pm);
                start(i, (TitleSearch.Offer) tag);
            }
            return true;
        }
        renderOver();
        return true;
    }

    /** Opens an app (at the title, where it allows), falling back to the app itself; the screen closes behind it. */
    private void start(Intent i, TitleSearch.Offer o) {
        try {
            if (i == null) throw new ActivityNotFoundException();
            ctx.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            if (o == null) return;
            Intent app = pm.getLeanbackLaunchIntentForPackage(o.pkg);
            if (app == null) app = pm.getLaunchIntentForPackage(o.pkg);
            if (app == null) return;
            try {
                ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                return;
            }
        }
        main.postDelayed(this::close, 400);
    }

    /** An app that has the title: its icon, its name, and how far it opens; the first one white. */
    private View appButton(TitleSearch.Offer o, boolean primary) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(14), 0, px(30), 0);
        b.setMinimumHeight(px(76));
        b.setBackground(pill(primary ? 0xFFFFFFFF : 0x1AFFFFFF, primary ? 0 : LINE, px(22)));
        ImageView icon = new ImageView(ctx);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(o.pkg));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        roundClip(icon, px(13));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(50), px(50));
        ilp.rightMargin = px(14);
        b.addView(icon, ilp);
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(R.string.font_medium, primary ? INK : 0xFFFFFFFF, 24);
        name.setText(Discover.shortLabel(Discover.appLabel(pm, o.pkg)));
        text.addView(name, wrap());
        TextView how = label(R.string.font_regular, primary ? 0x8C000000 : MUTED, 15);
        how.setText(o.how == TitleSearch.SHOW ? R.string.search_opens_show : o.how == TitleSearch.SEARCH ? R.string.search_opens_search : R.string.search_opens_app);
        text.addView(how, top(2));
        b.addView(text, wrap());
        return b;
    }

    private View plainButton(int iconRes, String text) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(26), 0, px(30), 0);
        b.setMinimumHeight(px(76));
        b.setBackground(pill(0x1AFFFFFF, LINE, px(22)));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(28), px(28));
        ilp.rightMargin = px(14);
        b.addView(icon, ilp);
        TextView t = label(R.string.font_medium, 0xFFFFFFFF, 24);
        t.setText(text);
        b.addView(t, wrap());
        return b;
    }

    private LinearLayout.LayoutParams buttonGap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(76));
        lp.rightMargin = px(18);
        return lp;
    }

    // ---- seen ------------------------------------------------------------------------------------

    private boolean isSeen(TitleSearch.Title t) {
        return prefs.getStringSet(SEEN, java.util.Collections.emptySet()).contains(seenKey(t));
    }

    private void markSeen(TitleSearch.Title t) {
        Set<String> set = new HashSet<>(prefs.getStringSet(SEEN, java.util.Collections.emptySet()));
        set.add(seenKey(t));
        prefs.edit().putStringSet(SEEN, set).apply();
    }

    private static String seenKey(TitleSearch.Title t) {
        return t.id.isEmpty() ? t.title.toLowerCase(Locale.ROOT) + "|" + t.year : t.id;
    }

    // ---- pieces ----------------------------------------------------------------------------------

    private void toast(String text) {
        TextView t = label(R.string.font_regular, INK, 22);
        t.setText(text);
        t.setPadding(px(28), px(16), px(28), px(16));
        t.setBackground(pill(0xF2FFFFFF, 0, px(30)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(60);
        view.addView(t, lp);
        t.setAlpha(0f);
        t.setTranslationY(px(30));
        t.animate().alpha(1f).translationY(0).setDuration(300).start();
        main.postDelayed(() -> t.animate().alpha(0f).setDuration(260).withEndAction(() -> view.removeView(t)).start(), 2200);
    }

    private static String score(double imdb) {
        return String.format(Locale.ROOT, "%.1f", imdb);
    }

    private LinearLayout.LayoutParams cell(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(w), px(h));
        lp.rightMargin = px(GAP);
        return lp;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = wrap();
        lp.topMargin = px(margin);
        return lp;
    }

    private TextView label(int font, int color, float size) {
        TextView t = new TextView(ctx);
        t.setTypeface(UiFont.get(ctx, font));
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caps(float size) {
        TextView t = label(R.string.font_medium, MUTED, size);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    private GradientDrawable pill(int color, int stroke, float r) {
        GradientDrawable d = rounded(color, r);
        if (stroke != 0) d.setStroke(Math.max(1, px(1.5f)), stroke);
        return d;
    }

    private Drawable ring(float r) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(r);
        d.setStroke(px(3), 0xEBFFFFFF);
        return d;
    }

    private static void roundClip(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private int px(float prototypePx) {
        return Math.round(prototypePx * unit);
    }

    /**
     * The billboard's fade into the background: solid from the left to `solid` of the width, half at `half`, gone by
     * `clear`; and up from the bottom to `fromBottom` of the height.
     */
    private static final class Scrim extends Drawable {
        private final int color;
        private final float solid, half, clear, fromBottom;
        private final Paint paint = new Paint();

        Scrim(int color, float solid, float half, float clear, float fromBottom) {
            this.color = color;
            this.solid = solid;
            this.half = half;
            this.clear = clear;
            this.fromBottom = fromBottom;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            int rgb = color & 0x00FFFFFF;
            paint.setShader(new LinearGradient(b.left, 0, b.right, 0,
                    new int[]{color, color, rgb | 0x8C000000, rgb},
                    new float[]{0f, solid, half, clear}, Shader.TileMode.CLAMP));
            c.drawRect(b, paint);
            paint.setShader(new LinearGradient(0, b.bottom, 0, b.bottom - b.height() * fromBottom,
                    color, rgb, Shader.TileMode.CLAMP));
            c.drawRect(b, paint);
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter cf) { }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
