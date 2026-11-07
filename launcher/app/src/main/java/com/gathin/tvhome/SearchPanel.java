package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * title_search: a glass pill under the widgets, and the panel it opens over Home. OK on the pill asks the TV's
 * voice input for a title (or the panel's field takes typing); the panel then shows the best match, the apps on
 * this TV that have it, and other matches. Choosing an app opens it as far as it allows (TitleSearch).
 */
final class SearchPanel {
    interface Host {
        /** Ask the TV's voice input for words; answer with heard(), or nothing when it is cancelled. */
        boolean listen();

        /** Up from the pill. */
        void onPillUp();

        /** Right from the pill: back to the app row. */
        void onPillRight();

        /** The pill took focus: the row's highlight steps back, as for a widget. */
        void onPillFocused();

        /** The panel closed: focus goes back to the pill. */
        void onClosed();
    }

    private static final int GLASS_TOP = 0x2EFFFFFF, GLASS_BOTTOM = 0x14FFFFFF;

    private final Context ctx;
    private final Host host;
    private final String country;
    private final PackageManager pm;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Bumped per lookup, so an answer that comes back after a newer search is dropped. */
    private int seq;

    final FrameLayout pill;
    private final TextView pillHint;
    final FrameLayout panel;
    private final TextView state;
    private final EditText field;
    private final FrameLayout orb;
    private final TextView message;
    private final LinearLayout result;
    private final ImageView poster;
    private final TextView title, meta, description;
    private final LinearLayout offers;
    private final LinearLayout others;
    private final LinearLayout otherChips;
    private final LinearLayout fallback;
    private List<TitleSearch.Title> titles = new ArrayList<>();
    private String words = "";

    SearchPanel(Context ctx, Host host, String country) {
        this.ctx = ctx;
        this.host = host;
        this.country = country;
        this.pm = ctx.getPackageManager();
        this.density = ctx.getResources().getDisplayMetrics().density;

        // The pill: glass like the top bar's pills; focus lights only its outline, so it stays glass.
        pill = new FrameLayout(ctx);
        pill.setFocusable(true);
        pill.setClickable(true);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), 0, dp(15), 0);
        pill.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView mic = new ImageView(ctx);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setAlpha(0.9f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(14), dp(14));
        mlp.rightMargin = dp(8);
        row.addView(mic, mlp);
        TextView pillText = label(R.string.font_regular, 0xCCFFFFFF, 11.5f);
        pillText.setText(R.string.search_pill);
        row.addView(pillText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pillHint = label(R.string.font_medium, 0x8CFFFFFF, 8f);
        pillHint.setText(R.string.search_pill_hint);
        row.addView(pillHint);
        pill.setBackground(glass(false));
        pill.setOnFocusChangeListener((v, focused) -> {
            pill.setBackground(glass(focused));
            pillText.setTextColor(focused ? 0xFFFFFFFF : 0xCCFFFFFF);
            pillHint.setTextColor(focused ? 0xCCFFFFFF : 0x8CFFFFFF);
            if (focused) host.onPillFocused();
        });
        pill.setOnClickListener(v -> open());
        pill.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    host.onPillUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    host.onPillRight();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                default:
                    return false;
            }
        });

        // The panel: over all of Home, dark enough that the row behind only shows as colour.
        panel = new FrameLayout(ctx);
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setBackgroundColor(0xFA0B0C10);
        LinearLayout page = new LinearLayout(ctx);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(40), dp(56), dp(28));
        panel.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout ask = new LinearLayout(ctx);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(ask);
        orb = new FrameLayout(ctx);
        orb.setFocusable(true);
        orb.setClickable(true);
        orb.setBackground(circle(false));
        ImageView orbMic = new ImageView(ctx);
        orbMic.setImageResource(R.drawable.ic_mic);
        orb.addView(orbMic, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        orb.setOnFocusChangeListener((v, f) -> {
            orb.setBackground(circle(f));
            orb.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(180).start();
        });
        orb.setOnClickListener(v -> listen());
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(48), dp(48));
        olp.rightMargin = dp(16);
        ask.addView(orb, olp);
        LinearLayout said = new LinearLayout(ctx);
        said.setOrientation(LinearLayout.VERTICAL);
        ask.addView(said, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        state = caps(0x99FFFFFF);
        said.addView(state);
        field = new EditText(ctx);
        field.setTypeface(UiFont.get(ctx, R.string.font_light));
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint(R.string.search_prompt);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setPadding(0, dp(2), 0, 0);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) search(field.getText().toString());
            return go;
        });
        field.setOnFocusChangeListener((v, f) -> field.setHintTextColor(f ? 0x99FFFFFF : 0x66FFFFFF));
        said.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        blp.topMargin = dp(24);
        page.addView(body, blp);

        message = label(R.string.font_regular, 0xB3FFFFFF, 14f);
        message.setSingleLine(false);
        message.setLineSpacing(0, 1.3f);
        LinearLayout empty = new LinearLayout(ctx);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setPadding(dp(64), 0, 0, 0);
        empty.addView(message, new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT));
        fallback = new LinearLayout(ctx);
        fallback.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        empty.addView(fallback, flp);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        result = new LinearLayout(ctx);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setVisibility(View.GONE);
        body.addView(result, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(130), dp(195));
        plp.rightMargin = dp(28);
        result.addView(poster, plp);
        LinearLayout facts = new LinearLayout(ctx);
        facts.setOrientation(LinearLayout.VERTICAL);
        result.addView(facts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title = label(R.string.font_medium, 0xFFFFFFFF, 28f);
        facts.addView(title);
        meta = label(R.string.font_regular, 0x99FFFFFF, 12f);
        facts.addView(meta, topMargin(dp(6)));
        description = label(R.string.font_regular, 0xD1FFFFFF, 12.5f);
        description.setSingleLine(false);
        description.setMaxLines(3);
        description.setLineSpacing(0, 1.3f);
        facts.addView(description, sized(dp(480), dp(8)));
        TextView watchOn = caps(0x99FFFFFF);
        watchOn.setText(R.string.search_watch_on);
        facts.addView(watchOn, topMargin(dp(18)));
        offers = new LinearLayout(ctx);
        offers.setOrientation(LinearLayout.HORIZONTAL);
        offers.setClipChildren(false);
        offers.setClipToPadding(false);
        facts.addView(offers, topMargin(dp(10)));

        others = new LinearLayout(ctx);
        others.setOrientation(LinearLayout.VERTICAL);
        others.setVisibility(View.GONE);
        TextView otherHead = caps(0x99FFFFFF);
        otherHead.setText(R.string.search_other_matches);
        others.addView(otherHead);
        otherChips = new LinearLayout(ctx);
        otherChips.setOrientation(LinearLayout.HORIZONTAL);
        otherChips.setClipChildren(false);
        others.addView(otherChips, topMargin(dp(10)));
        page.addView(others);
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** Opens the panel and asks for a title out loud; without voice input, the field takes typing instead. */
    void open() {
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(220).start();
        showMessage("", false);
        result.setVisibility(View.GONE);
        others.setVisibility(View.GONE);
        field.setText("");
        listen();
    }

    void close() {
        if (!isOpen()) return;
        seq++;
        hideKeyboard();
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        host.onClosed();
    }

    private void listen() {
        state.setText(R.string.search_listening);
        if (!host.listen()) {
            state.setText(R.string.search_typing);
            field.requestFocus();
            showKeyboard();
        } else {
            orb.requestFocus();
        }
    }

    /** What the voice input heard; nothing heard leaves the panel ready to type. */
    void heard(String text) {
        if (!isOpen()) return;
        if (text == null || text.trim().isEmpty()) {
            state.setText(R.string.search_typing);
            field.requestFocus();
            return;
        }
        field.setText(text);
        search(text);
    }

    private void search(String text) {
        String q = text.trim();
        if (q.isEmpty()) return;
        words = q;
        hideKeyboard();
        state.setText(R.string.search_looking);
        result.setVisibility(View.GONE);
        others.setVisibility(View.GONE);
        showMessage("", false);
        int mine = ++seq;
        worker.execute(() -> {
            List<TitleSearch.Title> found;
            try {
                found = TitleSearch.search(q, country, pm);
            } catch (Exception e) {
                found = null;
            }
            List<TitleSearch.Title> answer = found;
            main.post(() -> {
                if (mine != seq || !isOpen()) return;
                if (answer == null) {
                    state.setText(R.string.search_not_on_apps);
                    showMessage(ctx.getString(R.string.search_offline), false);
                    orb.requestFocus();
                    return;
                }
                titles = new ArrayList<>();
                for (TitleSearch.Title t : answer) if (!t.offers.isEmpty()) titles.add(t);
                if (titles.isEmpty()) {
                    state.setText(R.string.search_not_on_apps);
                    showMessage(ctx.getString(R.string.search_not_found, q), true);
                    return;
                }
                show(0);
            });
        });
    }

    private void showMessage(String text, boolean withFallback) {
        message.setText(text);
        message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        fallback.removeAllViews();
        if (!withFallback) return;
        List<TitleSearch.Offer> list = new ArrayList<>();
        if (TitleSearch.isInstalled(pm, TitleSearch.YOUTUBE)) list.add(TitleSearch.youtube());
        if (TitleSearch.isInstalled(pm, TitleSearch.PRIME)) list.add(TitleSearch.primeSearch());
        for (TitleSearch.Offer o : list) fallback.addView(offerButton(o, false, false), offerParams());
        if (fallback.getChildCount() > 0) fallback.getChildAt(0).requestFocus();
    }

    /** Shows one of the titles found, with the others below to switch to. */
    private void show(int index) {
        TitleSearch.Title t = titles.get(index);
        int n = t.offers.size();
        state.setText(n == 1 ? ctx.getString(R.string.search_on_one_app) : ctx.getString(R.string.search_on_apps, n));
        title.setText(t.title);
        String kind = ctx.getString("MOVIE".equals(t.kind) ? R.string.search_movie : R.string.search_show);
        meta.setText(t.year > 0 ? t.year + " · " + kind : kind);
        description.setText(t.description);
        description.setVisibility(t.description.isEmpty() ? View.GONE : View.VISIBLE);
        poster.setImageDrawable(rounded(0xFF1A1B22, dp(14)));
        int posterW = dp(130);
        int mine = seq;
        worker.execute(() -> {
            Bitmap b = TitleSearch.poster(t.poster, posterW);
            if (b != null) main.post(() -> {
                if (mine == seq && title.getText().toString().equals(t.title)) poster.setImageDrawable(roundedBitmap(b, posterW, dp(195), dp(14)));
            });
        });
        offers.removeAllViews();
        for (int i = 0; i < n; i++) offers.addView(offerButton(t.offers.get(i), i == 0, true), offerParams());
        boolean hasYoutube = false;
        for (TitleSearch.Offer o : t.offers) hasYoutube |= TitleSearch.YOUTUBE.equals(o.pkg);
        if (!hasYoutube && TitleSearch.isInstalled(pm, TitleSearch.YOUTUBE)) offers.addView(offerButton(TitleSearch.youtube(), false, true), offerParams());
        result.setVisibility(View.VISIBLE);
        showMessage("", false);

        otherChips.removeAllViews();
        for (int i = 0; i < titles.size(); i++) {
            if (i == index) continue;
            int at = i;
            TitleSearch.Title o = titles.get(i);
            otherChips.addView(chip(o.year > 0 ? o.title + " (" + o.year + ")" : o.title, () -> show(at)), chipParams());
            // Three fit across the screen even with long titles; a fourth would run off the edge.
            if (otherChips.getChildCount() == 3) break;
        }
        others.setVisibility(otherChips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        offers.getChildAt(0).requestFocus();
    }

    private void launch(TitleSearch.Offer o) {
        Intent i = TitleSearch.intentFor(o, words, pm);
        try {
            if (i == null) throw new ActivityNotFoundException();
            ctx.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            // The link was refused: open the app itself instead.
            Intent app = pm.getLeanbackLaunchIntentForPackage(o.pkg);
            if (app == null) app = pm.getLaunchIntentForPackage(o.pkg);
            if (app == null) return;
            try {
                ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                return;
            }
        }
        // Gone by the time Home comes back.
        main.postDelayed(this::close, 400);
    }

    // ---- pieces ----------------------------------------------------------------------------------

    private View offerButton(TitleSearch.Offer o, boolean best, boolean showHow) {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(10), dp(9), dp(16), dp(9));
        b.setMinimumWidth(dp(170));
        b.setBackground(offerBackground(false));
        ImageView icon = new ImageView(ctx);
        try {
            Drawable banner = pm.getApplicationIcon(o.pkg);
            icon.setImageDrawable(banner);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(34), dp(34));
        ilp.rightMargin = dp(10);
        b.addView(icon, ilp);
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        b.addView(text);
        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(R.string.font_medium, 0xFFFFFFFF, 13f);
        name.setText(appName(o.pkg));
        nameRow.addView(name);
        if (best) {
            TextView tag = label(R.string.font_medium, 0xD9FFFFFF, 7f);
            // Upper case in the text itself: setAllCaps measures the word before it is capitalised, which cut it short.
            tag.setText(ctx.getString(R.string.search_best_match).toUpperCase(java.util.Locale.getDefault()));
            tag.setLetterSpacing(0.12f);
            tag.setPadding(dp(5), dp(2), dp(5), dp(2));
            tag.setBackground(rounded(0x24FFFFFF, dp(5)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(6);
            nameRow.addView(tag, tlp);
        }
        // Wrap, not the vertical layout's default match_parent, so the name and its tag widen the button.
        text.addView(nameRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView terms = label(R.string.font_regular, 0x99FFFFFF, 9.5f);
        terms.setText(TitleSearch.YOUTUBE.equals(o.pkg) ? ctx.getString(R.string.search_youtube_terms)
                : ctx.getResources().getStringArray(R.array.title_search_terms)[o.terms]);
        text.addView(terms, topMargin(dp(2)));
        if (showHow) {
            TextView how = label(R.string.font_medium, howColor(o.how), 7.5f);
            how.setLetterSpacing(0.08f);
            how.setText("● " + ctx.getString(o.how == TitleSearch.SHOW ? R.string.search_opens_show
                    : o.how == TitleSearch.SEARCH ? R.string.search_opens_search : R.string.search_opens_app)
                    .toUpperCase(java.util.Locale.getDefault()));
            text.addView(how, topMargin(dp(4)));
        }
        b.setOnFocusChangeListener((v, f) -> {
            v.setBackground(offerBackground(f));
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(200).start();
        });
        b.setOnClickListener(v -> launch(o));
        return b;
    }

    private static int howColor(int how) {
        return how == TitleSearch.SHOW ? 0xFF7CE3A4 : how == TitleSearch.SEARCH ? 0xFF9CC7FF : 0x8CFFFFFF;
    }

    private String appName(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private View chip(String text, Runnable onClick) {
        TextView c = label(R.string.font_regular, 0xFFFFFFFF, 12f);
        c.setText(text);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(dp(14), dp(8), dp(14), dp(8));
        c.setBackground(chipBackground(false));
        c.setOnFocusChangeListener((v, f) -> {
            c.setBackground(chipBackground(f));
            c.setTextColor(f ? 0xFF111111 : 0xFFFFFFFF);
        });
        c.setOnClickListener(v -> onClick.run());
        return c;
    }

    private LinearLayout.LayoutParams offerParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        return lp;
    }

    private static LinearLayout.LayoutParams topMargin(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private static LinearLayout.LayoutParams sized(int width, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView label(int font, int color, float sp) {
        TextView t = new TextView(ctx);
        t.setTypeface(UiFont.get(ctx, font));
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView caps(int color) {
        TextView t = label(R.string.font_medium, color, 9f);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private Drawable glass(boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x33FFFFFF, 0x17FFFFFF} : new int[]{GLASS_TOP, GLASS_BOTTOM});
        d.setCornerRadius(dp(100));
        d.setStroke(focused ? dp(1) : Math.max(1, Math.round(density)), focused ? 0x80FFFFFF : 0x38FFFFFF);
        return d;
    }

    private Drawable circle(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(focused ? 0x33FFFFFF : 0x1AFFFFFF);
        d.setStroke(Math.max(1, Math.round(density)), focused ? 0xEBFFFFFF : 0x1FFFFFFF);
        return d;
    }

    private Drawable offerBackground(boolean focused) {
        GradientDrawable d = rounded(0xFF15161C, dp(14));
        d.setStroke(focused ? dp(1.5f) : Math.max(1, Math.round(density)), focused ? 0xEBFFFFFF : 0x1AFFFFFF);
        return d;
    }

    private Drawable chipBackground(boolean focused) {
        GradientDrawable d = rounded(focused ? 0xF5FFFFFF : 0x14FFFFFF, dp(100));
        if (!focused) d.setStroke(Math.max(1, Math.round(density)), 0x1AFFFFFF);
        return d;
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    /** The poster with its corners drawn rounded into the bitmap, centre-cropped to the panel's 2:3 box. */
    private Drawable roundedBitmap(Bitmap src, int w, int h, float r) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path p = new Path();
        p.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
        c.clipPath(p);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), null);
        return new BitmapDrawable(ctx.getResources(), out);
    }

    private void showKeyboard() {
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) field.post(() -> imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT));
    }

    private void hideKeyboard() {
        InputMethodManager imm = ctx.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Sizes the pill to the widget cards' width and the prototype's proportion of a tile (76px beside 660px). */
    void sizePill(int width, int tileHeight) {
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        int h = Math.round(tileHeight * 0.115f);
        if (lp != null && (lp.width != width || lp.height != h)) {
            lp.width = width;
            lp.height = h;
            pill.setLayoutParams(lp);
        }
        pill.setBackground(glass(pill.hasFocus()));
    }
}
