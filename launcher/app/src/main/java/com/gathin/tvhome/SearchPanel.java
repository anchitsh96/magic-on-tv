package com.gathin.tvhome;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.FocusFinder;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * title_search: a glass pill under the widgets, and the panel it opens over Home. OK on the pill opens the panel
 * listening (the TV's speech recogniser, with no screen of its own); holding OK opens it to type. The panel then
 * shows the best match, the apps on this TV that have it, and other matches. Choosing an app opens it as far as it
 * allows (TitleSearch). Sizes follow the prototype's 1920x1080 canvas, scaled to the screen's height.
 */
final class SearchPanel {
    interface Host {
        /** Ask once for the microphone; answer with micAnswered(). */
        void requestMic();

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
    /** The prototype's colours: the panel, a card, the hairlines, and the muted and faint text. */
    private static final int PANEL = 0xF00D0E13, CARD = 0xFF121318, LINE = 0x1AFFFFFF, MUTED = 0x99FFFFFF, INK = 0xFF111111;
    private static final String PREFS = "title_search", RECENT = "recent";

    private final Context ctx;
    private final Host host;
    private final String country;
    private final PackageManager pm;
    private final float density;
    /** One prototype pixel (of 1080 high) on this screen. */
    private final float unit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Bumped per lookup, so an answer that comes back after a newer search is dropped. */
    private int seq;

    final FrameLayout pill;
    final FrameLayout panel;
    private final FrameLayout card;
    private final TextView state;
    private final EditText field;
    private final FrameLayout orb;
    private final ImageView orbMic;
    private final Pulse pulse = new Pulse();
    private final Wave wave = new Wave();
    /** The TV's speech recogniser, listening without a screen of its own; null until first used, and after close. */
    private SpeechRecognizer recognizer;
    private boolean listening;

    private final LinearLayout listenView;
    private final LinearLayout resultView;
    private final LinearLayout noneView;
    private final TextView noneText;
    private final LinearLayout noneWhere;
    private final ImageView poster;
    private final TextView title, meta, description;
    private final LinearLayout where;
    private final TextView watchOn;
    private final LinearLayout bottom;
    private final TextView bottomHead;
    private final LinearLayout bottomChips;
    private List<TitleSearch.Title> titles = new ArrayList<>();
    private String words = "";

    SearchPanel(Context ctx, Host host, String country) {
        this.ctx = ctx;
        this.host = host;
        this.country = country;
        this.pm = ctx.getPackageManager();
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.unit = ctx.getResources().getDisplayMetrics().heightPixels / 1080f;

        // The pill: glass like the top bar's pills; focus lights only its outline, so it stays glass.
        pill = new FrameLayout(ctx);
        pill.setFocusable(true);
        pill.setClickable(true);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(30), 0, px(30), 0);
        pill.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView mic = new ImageView(ctx);
        mic.setImageResource(R.drawable.ic_mic);
        mic.setAlpha(0.9f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(px(28), px(28));
        mlp.rightMargin = px(16);
        row.addView(mic, mlp);
        TextView pillText = label(R.string.font_regular, 0xCCFFFFFF, 23);
        pillText.setText(R.string.search_pill);
        row.addView(pillText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pillHint = label(R.string.font_medium, 0x8CFFFFFF, 15);
        pillHint.setText(R.string.search_pill_hint);
        row.addView(pillHint);
        pill.setBackground(glass(false));
        pill.setOnFocusChangeListener((v, focused) -> {
            pill.setBackground(glass(focused));
            pillText.setTextColor(focused ? 0xFFFFFFFF : 0xCCFFFFFF);
            pillHint.setTextColor(focused ? 0xCCFFFFFF : 0x8CFFFFFF);
            if (focused) host.onPillFocused();
        });
        // OK speaks; holding OK opens the panel to type instead.
        pill.setOnClickListener(v -> speak());
        pill.setLongClickable(true);
        pill.setOnLongClickListener(v -> {
            type();
            return true;
        });
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

        // The panel: Home blurred and darkened behind (blurHome), and the prototype's card over it, 96px in from
        // the sides, from 150px down, 870px tall. Home stays focusable behind it, so the remote's arrows are kept
        // inside the panel while it is open: the next view in it, or staying put at its edge.
        panel = new FrameLayout(ctx) {
            @Override
            public View focusSearch(View focused, int direction) {
                View next = FocusFinder.getInstance().findNextFocus(this, focused, direction);
                return next != null ? next : focused;
            }
        };
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setBackgroundColor(0x8C000000);
        card = new FrameLayout(ctx);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setPadding(px(64), px(56), px(64), px(50));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(870), Gravity.TOP);
        clp.leftMargin = clp.rightMargin = px(96);
        clp.topMargin = px(150);
        panel.addView(card, clp);
        setHue(0xFF555555);

        // The question: the mic orb and what was heard (or typed).
        LinearLayout ask = new LinearLayout(ctx);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        ask.setClipChildren(false);
        card.addView(ask, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(96), Gravity.TOP));
        // The orb never changes size, so nothing fights over its scale: focus shows as its outline, listening as
        // the prototype's ring pulsing out from it (Pulse, in a box wide enough for the ring).
        FrameLayout orbBox = new FrameLayout(ctx);
        orbBox.setClipChildren(false);
        View pulseView = new View(ctx);
        pulseView.setBackground(pulse);
        orbBox.addView(pulseView, new FrameLayout.LayoutParams(px(180), px(180), Gravity.CENTER));
        orb = new FrameLayout(ctx);
        orb.setFocusable(true);
        orb.setClickable(true);
        orb.setBackground(orbBackground(false, false));
        orbMic = new ImageView(ctx);
        orbMic.setImageResource(R.drawable.ic_mic);
        orb.addView(orbMic, new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER));
        orb.setOnFocusChangeListener((v, f) -> orb.setBackground(orbBackground(listening, f)));
        orb.setOnClickListener(v -> speak());
        orbBox.addView(orb, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(px(96), px(96));
        olp.rightMargin = px(28);
        ask.addView(orbBox, olp);
        LinearLayout said = new LinearLayout(ctx);
        said.setOrientation(LinearLayout.VERTICAL);
        ask.addView(said, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        state = caps();
        said.addView(state);
        field = new EditText(ctx);
        field.setTypeface(UiFont.get(ctx, R.string.font_light));
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(54));
        field.setTextColor(0xFFFFFFFF);
        field.setHintTextColor(0x66FFFFFF);
        field.setHint(R.string.search_prompt);
        field.setSingleLine(true);
        field.setIncludeFontPadding(false);
        field.setBackground(null);
        field.setPadding(0, px(4), 0, 0);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) search(field.getText().toString());
            return go;
        });
        field.setOnFocusChangeListener((v, f) -> {
            // Moving to the field to type ends the listening.
            if (f && listening) {
                stopListening();
                state.setText(R.string.search_typing);
            }
        });
        said.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Listening: the prototype's bars under the question, moving with the voice (Wave).
        listenView = new LinearLayout(ctx);
        listenView.setOrientation(LinearLayout.VERTICAL);
        View waveView = new View(ctx);
        waveView.setBackground(wave);
        listenView.addView(waveView, new LinearLayout.LayoutParams(px(28 * 18), px(120)));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        llp.leftMargin = px(124);
        llp.topMargin = px(96 + 70);
        card.addView(listenView, llp);

        // A result: poster, facts, and the apps that have it.
        resultView = new LinearLayout(ctx);
        resultView.setOrientation(LinearLayout.HORIZONTAL);
        resultView.setClipChildren(false);
        resultView.setVisibility(View.GONE);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        rlp.topMargin = px(96 + 48);
        card.addView(resultView, rlp);
        poster = new ImageView(ctx);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(px(250), px(375));
        plp.rightMargin = px(52);
        resultView.addView(poster, plp);
        LinearLayout facts = new LinearLayout(ctx);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setClipChildren(false);
        resultView.addView(facts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title = label(R.string.font_medium, 0xFFFFFFFF, 56);
        facts.addView(title);
        meta = label(R.string.font_regular, MUTED, 22);
        facts.addView(meta, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(12), 0));
        description = label(R.string.font_regular, 0xD1FFFFFF, 23);
        description.setSingleLine(false);
        description.setMaxLines(2);
        description.setLineSpacing(0, 1.45f);
        facts.addView(description, margins(px(900), px(16), 0));
        watchOn = caps();
        watchOn.setText(R.string.search_watch_on);
        facts.addView(watchOn, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(36), 0));
        where = row();
        facts.addView(where, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(16), 0));

        // Nothing on the TV's apps: say so, and offer the searches that take the words as said.
        noneView = new LinearLayout(ctx);
        noneView.setOrientation(LinearLayout.VERTICAL);
        noneView.setClipChildren(false);
        noneView.setVisibility(View.GONE);
        noneText = label(R.string.font_regular, MUTED, 26);
        noneText.setSingleLine(false);
        noneText.setLineSpacing(0, 1.45f);
        noneView.addView(noneText, new LinearLayout.LayoutParams(px(900), ViewGroup.LayoutParams.WRAP_CONTENT));
        noneWhere = row();
        noneView.addView(noneWhere, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(28), 0));
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        nlp.leftMargin = px(124);
        nlp.topMargin = px(96 + 56);
        card.addView(noneView, nlp);

        // Along the bottom: recent searches while listening, other matches under a result.
        bottom = new LinearLayout(ctx);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottomHead = caps();
        bottom.addView(bottomHead);
        bottomChips = row();
        bottom.addView(bottomChips, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(16), 0));
        card.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    /** Opens the panel listening for a title; the microphone is asked for once, the first time. */
    void speak() {
        show();
        seq++;
        view(listenView);
        field.setText("");
        state.setText(R.string.search_listening);
        showRecent();
        orb.requestFocus();
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestMic();
            return;
        }
        startListening();
    }

    /** The answer to requestMic: listen now, or type when the microphone was refused. */
    void micAnswered(boolean granted) {
        if (!isOpen()) return;
        if (granted) startListening();
        else type();
    }

    /** Opens the panel with the field ready for typing. */
    void type() {
        stopListening();
        show();
        seq++;
        view(listenView);
        field.setText("");
        state.setText(R.string.search_typing);
        showRecent();
        field.requestFocus();
        showKeyboard();
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            type();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Listener());
        }
        setListening(true);
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1));
    }

    private void stopListening() {
        if (recognizer != null) recognizer.cancel();
        setListening(false);
    }

    /** Listening: the orb white with a dark mic, its ring pulsing, and the bars moving with the voice. */
    private void setListening(boolean on) {
        listening = on;
        orb.setBackground(orbBackground(on, orb.hasFocus()));
        orbMic.setColorFilter(on ? INK : 0xFFFFFFFF);
        pulse.setRunning(on);
        wave.setRunning(on);
    }

    private final class Listener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
            state.setText(R.string.search_listening);
        }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Roughly -2 dB in a quiet room to 10 dB for speech; the bars ease towards it frame by frame.
            if (listening) wave.setLevel((rmsdB + 2f) / 12f);
        }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() {
            setListening(false);
            state.setText(R.string.search_looking);
        }

        @Override
        public void onError(int error) {
            setListening(false);
            if (!isOpen()) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                host.requestMic();
                return;
            }
            didntCatch();
        }

        @Override
        public void onResults(Bundle results) {
            setListening(false);
            heard(first(results));
        }

        @Override
        public void onPartialResults(Bundle partial) {
            String text = first(partial);
            if (text != null && !text.isEmpty()) field.setText(text);
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }

        private String first(Bundle b) {
            ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
    }

    private void didntCatch() {
        state.setText(R.string.search_didnt_catch);
        view(noneView);
        noneText.setText(R.string.search_try_again);
        noneWhere.removeAllViews();
        orb.requestFocus();
    }

    /** What the recogniser heard; nothing heard leaves the mic ready to try again. */
    private void heard(String text) {
        if (!isOpen()) return;
        if (text == null || text.trim().isEmpty()) {
            didntCatch();
            return;
        }
        field.setText(text);
        search(text);
    }

    private void show() {
        if (isOpen()) return;
        blurHome(true);
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(220).start();
    }

    void close() {
        if (!isOpen()) return;
        panel.animate().cancel();
        // Let go of the microphone with the panel.
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        setListening(false);
        seq++;
        hideKeyboard();
        blurHome(false);
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        host.onClosed();
    }

    /** The prototype's blur(14px) on Home behind the panel (Android 12 and up; older sets only darken it). */
    private void blurHome(boolean on) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !(panel.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) panel.getParent();
        RenderEffect blur = on ? RenderEffect.createBlurEffect(px(14), px(14), Shader.TileMode.CLAMP) : null;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != panel) c.setRenderEffect(blur);
        }
    }

    private void search(String text) {
        String q = text.trim();
        if (q.isEmpty()) return;
        words = q;
        stopListening();
        hideKeyboard();
        state.setText(R.string.search_looking);
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
                remember(q);
                if (answer == null) {
                    state.setText(R.string.search_not_on_apps);
                    view(noneView);
                    noneText.setText(R.string.search_offline);
                    noneWhere.removeAllViews();
                    bottom.setVisibility(View.GONE);
                    orb.requestFocus();
                    return;
                }
                titles = new ArrayList<>();
                // The title asked for comes first even when no app here is listed for it (a new release the
                // catalogue has not caught up with); otherwise only titles some app here has.
                for (int i = 0; i < answer.size(); i++) {
                    TitleSearch.Title t = answer.get(i);
                    if (!t.offers.isEmpty() || (i == 0 && TitleSearch.matches(t.title, q))) titles.add(t);
                }
                if (titles.isEmpty()) {
                    nothingFound(q);
                    return;
                }
                show(0);
            });
        });
    }

    private void nothingFound(String q) {
        state.setText(R.string.search_not_on_apps);
        view(noneView);
        noneText.setText(ctx.getString(R.string.search_not_found, q));
        noneWhere.removeAllViews();
        List<TitleSearch.Offer> list = new ArrayList<>();
        if (TitleSearch.isInstalled(pm, TitleSearch.YOUTUBE)) list.add(TitleSearch.youtube());
        if (TitleSearch.isInstalled(pm, TitleSearch.PRIME)) list.add(TitleSearch.primeSearch());
        for (TitleSearch.Offer o : list) noneWhere.addView(offerButton(o, false), gap(18));
        setHue(0xFF444444);
        showRecent();
        if (noneWhere.getChildCount() > 0) noneWhere.getChildAt(0).requestFocus();
    }

    /** Shows one of the titles found, with the others along the bottom to switch to. */
    private void show(int index) {
        TitleSearch.Title t = titles.get(index);
        boolean listed = !t.offers.isEmpty();
        List<TitleSearch.Offer> offers = listed ? t.offers : TitleSearch.lookFor(pm);
        int n = offers.size();
        state.setText(!listed ? ctx.getString(R.string.search_not_listed)
                : n == 1 ? ctx.getString(R.string.search_on_one_app) : ctx.getString(R.string.search_on_apps, n));
        watchOn.setText(listed ? R.string.search_watch_on : R.string.search_look_for_it);
        title.setText(t.title);
        String kind = ctx.getString("MOVIE".equals(t.kind) ? R.string.search_movie : R.string.search_show);
        String when = t.year > 0 ? t.year + " · " + kind : kind;
        meta.setText(t.inCinemas ? when + " · " + ctx.getString(R.string.search_in_cinemas) : when);
        description.setText(t.description);
        description.setVisibility(t.description.isEmpty() ? View.GONE : View.VISIBLE);
        int pw = px(250), ph = px(375);
        poster.setImageDrawable(rounded(0xFF1A1B22, px(22)));
        int mine = seq;
        worker.execute(() -> {
            Bitmap b = TitleSearch.poster(t.poster, pw);
            if (b != null) main.post(() -> {
                if (mine == seq && title.getText().toString().equals(t.title)) poster.setImageDrawable(roundedBitmap(b, pw, ph, px(22)));
            });
        });
        where.removeAllViews();
        for (int i = 0; i < n; i++) where.addView(offerButton(offers.get(i), listed && i == 0), gap(18));
        boolean hasYoutube = false;
        for (TitleSearch.Offer o : offers) hasYoutube |= TitleSearch.YOUTUBE.equals(o.pkg);
        if (!hasYoutube && TitleSearch.isInstalled(pm, TitleSearch.YOUTUBE)) where.addView(offerButton(TitleSearch.youtube(), false), gap(18));
        setHue(listed ? appColor(offers.get(0).pkg) : 0xFF555555);
        if (where.getChildCount() == 0) {
            nothingFound(words);
            return;
        }
        view(resultView);

        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < titles.size() && labels.size() < 3; i++) {
            if (i == index) continue;
            int at = i;
            TitleSearch.Title o = titles.get(i);
            labels.add(o.year > 0 ? o.title + " (" + o.year + ")" : o.title);
            actions.add(() -> show(at));
        }
        chips(R.string.search_other_matches, labels, actions);
        where.getChildAt(0).requestFocus();
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

    // ---- recent searches -------------------------------------------------------------------------

    private void remember(String q) {
        List<String> list = recent();
        list.removeIf(s -> s.equalsIgnoreCase(q));
        list.add(0, q);
        while (list.size() > 4) list.remove(list.size() - 1);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(RECENT, TextUtils.join("\n", list)).apply();
    }

    private List<String> recent() {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(RECENT, "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    private void showRecent() {
        List<String> labels = recent();
        List<Runnable> actions = new ArrayList<>();
        for (String q : labels) actions.add(() -> {
            field.setText(q);
            search(q);
        });
        chips(R.string.search_recent, labels, actions);
    }

    private void chips(int head, List<String> labels, List<Runnable> actions) {
        bottomHead.setText(head);
        bottomChips.removeAllViews();
        for (int i = 0; i < labels.size(); i++) bottomChips.addView(chip(labels.get(i), actions.get(i)), gap(16));
        bottom.setVisibility(labels.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- pieces ----------------------------------------------------------------------------------

    /** Shows one of the three bodies: listening, a result, or nothing found. */
    private void view(View v) {
        listenView.setVisibility(v == listenView ? View.VISIBLE : View.GONE);
        resultView.setVisibility(v == resultView ? View.VISIBLE : View.GONE);
        noneView.setVisibility(v == noneView ? View.VISIBLE : View.GONE);
        if (v != resultView) setHue(0xFF555555);
    }

    /** An app that has the title, as the prototype draws it: its colour glowing from the left, the icon, the name. */
    private View offerButton(TitleSearch.Offer o, boolean best) {
        int color = appColor(o.pkg);
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(px(18), px(16), px(30), px(16));
        // The prototype's 330px buttons; places to look come four at a time, so they take only what they need.
        b.setMinimumWidth(o.terms == TitleSearch.LOOK ? 0 : px(330));
        b.setMinimumHeight(px(104));
        b.setBackground(offerBackground(color));
        ImageView icon = new ImageView(ctx);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(o.pkg));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        icon.setClipToOutline(true);
        icon.setBackground(rounded(0, px(16)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(68), px(68));
        ilp.rightMargin = px(18);
        b.addView(icon, ilp);
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        b.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout nameRow = new LinearLayout(ctx);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = label(R.string.font_medium, 0xFFFFFFFF, 26);
        name.setText(appName(o.pkg));
        nameRow.addView(name);
        if (best) {
            TextView tag = label(R.string.font_medium, 0xD9FFFFFF, 13);
            // Upper case in the text itself: setAllCaps measures the word before it is capitalised.
            tag.setText(ctx.getString(R.string.search_best_match).toUpperCase(Locale.getDefault()));
            tag.setLetterSpacing(0.12f);
            tag.setPadding(px(10), px(4), px(10), px(4));
            tag.setBackground(rounded(0x24FFFFFF, px(10)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = px(12);
            nameRow.addView(tag, tlp);
        }
        // Wrap, not the vertical layout's default match_parent, so the name and its tag widen the button.
        text.addView(nameRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView terms = label(R.string.font_regular, MUTED, 18);
        terms.setText(o.terms == TitleSearch.LOOK ? ctx.getString(o.how == TitleSearch.SEARCH ? R.string.search_find_it : R.string.search_look_there)
                : TitleSearch.YOUTUBE.equals(o.pkg) ? ctx.getString(R.string.search_youtube_terms)
                : ctx.getResources().getStringArray(R.array.title_search_terms)[o.terms]);
        text.addView(terms, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(3), 0));
        TextView how = label(R.string.font_medium, howColor(o.how), 15);
        how.setLetterSpacing(0.08f);
        how.setText(("● " + ctx.getString(o.how == TitleSearch.SHOW ? R.string.search_opens_show
                : o.how == TitleSearch.SEARCH ? R.string.search_opens_search : R.string.search_opens_app))
                .toUpperCase(Locale.getDefault()));
        text.addView(how, margins(ViewGroup.LayoutParams.WRAP_CONTENT, px(8), 0));
        // Focus: 5% larger with a 3px white ring, as in the prototype. The ring is a foreground over the whole
        // button and the rows around it never clip, so it stays whole as the button grows.
        b.setOnFocusChangeListener((v, f) -> {
            v.setForeground(f ? ring(px(26)) : null);
            v.animate().scaleX(f ? 1.05f : 1f).scaleY(f ? 1.05f : 1f).setDuration(220).start();
        });
        b.setOnClickListener(v -> launch(o));
        return b;
    }

    private View chip(String text, Runnable onClick) {
        TextView c = label(R.string.font_regular, 0xFFFFFFFF, 24);
        c.setText(text);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(px(28), px(16), px(28), px(16));
        c.setBackground(chipBackground(false));
        c.setOnFocusChangeListener((v, f) -> {
            c.setBackground(chipBackground(f));
            c.setTextColor(f ? INK : 0xFFFFFFFF);
            v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(200).start();
        });
        c.setOnClickListener(v -> onClick.run());
        return c;
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

    private int appColor(String pkg) {
        try {
            return AppAdapter.vividColor(pm.getApplicationIcon(pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return 0xFF555555;
        }
    }

    /** The card's background: the panel colour, its hairline, and a glow in the best app's colour, left of centre. */
    private void setHue(int color) {
        GradientDrawable base = rounded(PANEL, px(40));
        base.setStroke(Math.max(1, px(1.5f)), LINE);
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setCornerRadius(px(40));
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.18f, 0.4f);
        glow.setGradientRadius(px(1100));
        glow.setColors(new int[]{(color & 0x00FFFFFF) | 0x61000000, color & 0x00FFFFFF});
        card.setBackground(new LayerDrawable(new Drawable[]{base, glow}));
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    private LinearLayout.LayoutParams gap(int g) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(g);
        return lp;
    }

    private static LinearLayout.LayoutParams margins(int width, int top, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.rightMargin = right;
        return lp;
    }

    /** Text sized in prototype pixels. */
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

    /** The prototype's small heading: 18px, upper case, spaced out. */
    private TextView caps() {
        TextView t = label(R.string.font_medium, MUTED, 18);
        t.setAllCaps(true);
        t.setLetterSpacing(0.14f);
        return t;
    }

    private Drawable glass(boolean focused) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x33FFFFFF, 0x17FFFFFF} : new int[]{GLASS_TOP, GLASS_BOTTOM});
        d.setCornerRadius(px(100));
        d.setStroke(Math.max(1, px(1.5f)), focused ? 0x80FFFFFF : 0x2EFFFFFF);
        return d;
    }

    /** The orb: glass with a hairline, its outline lit while focused, or solid white while listening. */
    private Drawable orbBackground(boolean listening, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(listening ? 0xFFFFFFFF : 0x1AFFFFFF);
        if (focused) d.setStroke(px(3), 0xEBFFFFFF);
        else if (!listening) d.setStroke(Math.max(1, px(1.5f)), LINE);
        return d;
    }

    private Drawable offerBackground(int color) {
        GradientDrawable base = rounded(CARD, px(26));
        base.setStroke(Math.max(1, px(1.5f)), LINE);
        GradientDrawable glow = new GradientDrawable();
        glow.setCornerRadius(px(26));
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0f, 0.5f);
        glow.setGradientRadius(px(280));
        glow.setColors(new int[]{(color & 0x00FFFFFF) | 0x73000000, color & 0x00FFFFFF});
        return new LayerDrawable(new Drawable[]{base, glow});
    }

    private Drawable ring(float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setStroke(px(3), 0xEBFFFFFF);
        return d;
    }

    private Drawable chipBackground(boolean focused) {
        GradientDrawable d = rounded(focused ? 0xFFFFFFFF : 0x14FFFFFF, px(36));
        if (!focused) d.setStroke(Math.max(1, px(1.5f)), LINE);
        return d;
    }

    private static GradientDrawable rounded(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    /** The poster with its corners drawn rounded into the bitmap, centre-cropped to the 2:3 box. */
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

    private int px(float prototypePx) {
        return Math.round(prototypePx * unit);
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

    // ---- listening animations --------------------------------------------------------------------

    /** Base for the two listening animations: frames of their own, about 60 a second, only while running. */
    private abstract static class Animated extends Drawable implements Runnable {
        protected boolean running;
        protected long start;

        void setRunning(boolean on) {
            if (on == running) return;
            running = on;
            unscheduleSelf(this);
            if (on) {
                start = SystemClock.uptimeMillis();
                scheduleSelf(this, start + 16);
            }
            invalidateSelf();
        }

        @Override
        public void run() {
            if (!running) return;
            tick();
            invalidateSelf();
            scheduleSelf(this, SystemClock.uptimeMillis() + 16);
        }

        void tick() { }

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

    /**
     * The prototype's ring round the orb while listening: 14px out from it, 2px of white at 35%, growing from 85%
     * to 135% of that size and fading out every 1.4 s.
     */
    private final class Pulse extends Animated {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pulse() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas c) {
            if (!running) return;
            Rect b = getBounds();
            float p = ((SystemClock.uptimeMillis() - start) % 1400) / 1400f;
            // ease-out, as the prototype's keyframes
            float e = 1f - (1f - p) * (1f - p);
            float r = (px(96) / 2f + px(14)) * (0.85f + 0.5f * e);
            paint.setStrokeWidth(px(2));
            paint.setAlpha(Math.round(255 * 0.35f * 0.8f * (1f - e)));
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint);
        }
    }

    /**
     * The prototype's 28 bars under the question: 10px wide, 8px apart, each rising and falling on its own beat
     * (900 ms, 70 ms behind the one before). The voice sets how far they rise; the level the recogniser reports
     * jumps, so each frame moves a fifth of the way to it.
     */
    private final class Wave extends Animated {
        private static final int BARS = 28;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private float target, level;

        Wave() {
            paint.setColor(0xD9FFFFFF);
        }

        void setLevel(float l) {
            target = Math.max(0f, Math.min(1f, l));
        }

        @Override
        void setRunning(boolean on) {
            super.setRunning(on);
            if (on) target = level = 0f;
        }

        @Override
        void tick() {
            level += (target - level) * 0.2f;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = px(10), step = px(18), cy = b.exactCenterY(), min = px(14);
            long t = SystemClock.uptimeMillis() - start;
            for (int i = 0; i < BARS; i++) {
                float max = px(30 + Math.round(Math.abs(Math.sin(i * 1.7)) * 90));
                float h = min;
                if (running) {
                    // 0..1..0 over 1.8 s, the prototype's alternate 900 ms ease-in-out, each bar 70 ms behind.
                    float p = ((t + i * 70L) % 1800) / 900f;
                    float tri = p <= 1f ? p : 2f - p;
                    float ease = (float) (0.5 - 0.5 * Math.cos(Math.PI * tri));
                    h = min + (max - min) * ease * (0.25f + 0.75f * level);
                }
                float x = b.left + i * step;
                bar.set(x, cy - h / 2f, x + w, cy + h / 2f);
                c.drawRoundRect(bar, w / 2f, w / 2f, paint);
            }
        }
    }
}
