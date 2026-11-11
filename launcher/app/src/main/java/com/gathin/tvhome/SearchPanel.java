package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * title_search: the search pill under the widgets. At rest it holds the greeting; OK makes it listen in place (the
 * TV's speech recogniser, with no screen of its own): a white mic, the words as they come, and bars that move with
 * the voice. What was said opens the search screen on its answer; Down while listening, or holding OK, opens the
 * screen to browse. The pill never changes size. Sizes follow the prototype's 1920x1080 canvas.
 */
final class SearchPanel {
    interface Host {
        /** Ask once for the microphone; answer with micAnswered(). */
        void requestMic();

        /** Up or Right from the pill: back to the app row. */
        void onPillUp();

        /** Left from the pill: to the widgets. */
        void onPillLeft();

        /** The pill took focus: the row's highlight steps back, as for a widget. */
        void onPillFocused();

        /** Open the search screen on what was said, or to browse when null. */
        void openScreen(String said);
    }

    private static final int GLASS_TOP = 0x2EFFFFFF, GLASS_BOTTOM = 0x14FFFFFF, PANEL = 0xF00D0E13, INK = 0xFF111111;

    private final Context ctx;
    private final Host host;
    private final float unit;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Voice voice;

    final FrameLayout pill;
    private final LinearLayout rest, listenRow;
    private final TextView greeting, hint, said;
    private final Voice.Pulse pulse;
    private final Voice.Wave wave;
    private boolean listening;

    SearchPanel(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.unit = ctx.getResources().getDisplayMetrics().heightPixels / 1080f;
        this.voice = new Voice(ctx);

        pill = new FrameLayout(ctx);
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setClipChildren(false);

        // At rest: the search icon, the greeting, and "OK to search" while focused, 24px in from either edge.
        rest = new LinearLayout(ctx);
        rest.setOrientation(LinearLayout.HORIZONTAL);
        rest.setGravity(Gravity.CENTER_VERTICAL);
        rest.setPadding(px(24), 0, px(24), 0);
        pill.addView(rest, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_search);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(28), px(28));
        ilp.rightMargin = px(14);
        rest.addView(icon, ilp);
        greeting = label(R.string.font_regular, 0xD9FFFFFF, 22);
        rest.addView(greeting, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hint = label(R.string.font_medium, 0x8CFFFFFF, 15);
        hint.setText(R.string.search_greeting_hint);
        hint.setVisibility(View.INVISIBLE);
        rest.addView(hint);

        // Listening: a white mic with its ring pulsing, what's been heard (a hint until then), and the bars.
        listenRow = new LinearLayout(ctx);
        listenRow.setOrientation(LinearLayout.HORIZONTAL);
        listenRow.setGravity(Gravity.CENTER_VERTICAL);
        listenRow.setPadding(px(10), 0, px(24), 0);
        listenRow.setClipChildren(false);
        listenRow.setVisibility(View.GONE);
        pill.addView(listenRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout micBox = new FrameLayout(ctx);
        micBox.setClipChildren(false);
        pulse = new Voice.Pulse(px(25), px(8), px(2));
        View ring = new View(ctx);
        ring.setBackground(pulse);
        micBox.addView(ring, new FrameLayout.LayoutParams(px(90), px(90), Gravity.CENTER));
        FrameLayout mic = new FrameLayout(ctx);
        GradientDrawable white = new GradientDrawable();
        white.setShape(GradientDrawable.OVAL);
        white.setColor(0xFFFFFFFF);
        mic.setBackground(white);
        ImageView micIcon = new ImageView(ctx);
        micIcon.setImageResource(R.drawable.ic_mic);
        micIcon.setColorFilter(INK);
        mic.addView(micIcon, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER));
        micBox.addView(mic, new FrameLayout.LayoutParams(px(50), px(50), Gravity.CENTER));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(px(50), px(50));
        mlp.rightMargin = px(14);
        listenRow.addView(micBox, mlp);
        said = label(R.string.font_light, 0xFFFFFFFF, 21);
        said.setEllipsize(TextUtils.TruncateAt.START);   // the latest words stay in view
        listenRow.addView(said, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wave = new Voice.Wave(9, px(5), px(9), px(6), px(8), px(24));
        View bars = new View(ctx);
        bars.setBackground(wave);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(px(9 * 9), px(34));
        wlp.leftMargin = px(14);
        listenRow.addView(bars, wlp);

        pill.setBackground(background(false, false));
        pill.setOnFocusChangeListener((v, focused) -> {
            if (!focused && listening) stop();
            refresh();
            if (focused) host.onPillFocused();
        });
        pill.setOnClickListener(v -> {
            if (!listening) speak();
        });
        pill.setLongClickable(true);
        pill.setOnLongClickListener(v -> {
            stop();
            host.openScreen(null);
            return true;
        });
        pill.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    stop();
                    host.onPillUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    stop();
                    host.onPillLeft();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // Down while listening: browse the search screen instead.
                    if (listening) {
                        stop();
                        host.openScreen(null);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    /** The greeting the header used to show ("Hey, welcome back"). */
    void setGreeting(CharSequence text) {
        greeting.setText(text);
    }

    boolean isListening() {
        return listening;
    }

    /** Listens in the pill; the microphone is asked for once, the first time. */
    void speak() {
        if (!voice.available()) {
            host.openScreen(null);
            return;
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.requestMic();
            return;
        }
        setListening(true);
        said.setText(R.string.search_say);
        said.setTextColor(0x66FFFFFF);
        voice.listen(new Voice.Ear() {
            @Override
            public void onLevel(float level) {
                wave.setLevel(level);
            }

            @Override
            public void onPartial(String text) {
                said.setText(text);
                said.setTextColor(0xFFFFFFFF);
            }

            @Override
            public void onHeard(String text) {
                if (!listening) return;
                if (text == null || text.trim().isEmpty()) {
                    // Nothing caught: back to the greeting, ready for another OK.
                    setListening(false);
                    return;
                }
                said.setText(text);
                said.setTextColor(0xFFFFFFFF);
                wave.setRunning(false);
                main.postDelayed(() -> {
                    setListening(false);
                    host.openScreen(text.trim());
                }, 350);
            }

            @Override
            public void onNeedMic() {
                setListening(false);
                host.requestMic();
            }
        });
    }

    /** The answer to requestMic: listen now, or open the screen to browse when the microphone was refused. */
    void micAnswered(boolean granted) {
        if (granted) speak();
        else host.openScreen(null);
    }

    /** Stops listening and lets go of the microphone. */
    void stop() {
        main.removeCallbacksAndMessages(null);
        voice.release();
        if (listening) setListening(false);
    }

    private void setListening(boolean on) {
        listening = on;
        rest.setVisibility(on ? View.GONE : View.VISIBLE);
        listenRow.setVisibility(on ? View.VISIBLE : View.GONE);
        pulse.setRunning(on);
        wave.setRunning(on);
        refresh();
    }

    private void refresh() {
        boolean focused = pill.hasFocus();
        pill.setBackground(background(focused, listening));
        greeting.setTextColor(focused ? 0xFFFFFFFF : 0xD9FFFFFF);
        hint.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
    }

    /** Glass like the top bar's pills, its outline lit while focused; darker while listening. */
    private Drawable background(boolean focused, boolean listening) {
        GradientDrawable d = listening ? new GradientDrawable() : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{0x33FFFFFF, 0x17FFFFFF} : new int[]{GLASS_TOP, GLASS_BOTTOM});
        if (listening) d.setColor(PANEL);
        d.setCornerRadius(px(100));
        d.setStroke(Math.max(1, px(1.5f)), listening ? 0x47FFFFFF : focused ? 0x80FFFFFF : 0x2EFFFFFF);
        return d;
    }

    /** Sizes the pill to the widget cards' width and the prototype's 72px height. */
    void sizePill(int width) {
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        int h = px(72);
        if (lp != null && (lp.width != width || lp.height != h)) {
            lp.width = width;
            lp.height = h;
            pill.setLayoutParams(lp);
        }
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

    private int px(float prototypePx) {
        return Math.round(prototypePx * unit);
    }
}
