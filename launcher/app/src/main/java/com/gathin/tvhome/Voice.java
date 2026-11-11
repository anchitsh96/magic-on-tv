package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * title_search: the TV's speech recogniser, listening with no screen of its own (the search pill and the search
 * screen's ask bar draw their own), and the two animations that show it is listening.
 */
final class Voice {
    interface Ear {
        /** How loud, 0 to 1, for the bars. */
        void onLevel(float level);

        /** The words so far. */
        void onPartial(String text);

        /** What was said, or null when nothing was caught. */
        void onHeard(String text);

        /** The microphone hasn't been allowed. */
        void onNeedMic();
    }

    private final Context ctx;
    private SpeechRecognizer recognizer;
    private Ear ear;

    Voice(Context ctx) {
        this.ctx = ctx;
    }

    boolean available() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    void listen(Ear e) {
        ear = e;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Listener());
        }
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1));
    }

    /** Stops listening, saying nothing to the ear. */
    void cancel() {
        ear = null;
        if (recognizer != null) recognizer.cancel();
    }

    /** Lets go of the microphone. */
    void release() {
        cancel();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private final class Listener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) { }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) {
            // Roughly -2 dB in a quiet room to 10 dB for speech.
            if (ear != null) ear.onLevel((rmsdB + 2f) / 12f);
        }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            Ear e = ear;
            ear = null;
            if (e == null) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) e.onNeedMic();
            else e.onHeard(null);
        }

        @Override
        public void onResults(Bundle results) {
            Ear e = ear;
            ear = null;
            if (e != null) e.onHeard(first(results));
        }

        @Override
        public void onPartialResults(Bundle partial) {
            String text = first(partial);
            if (ear != null && text != null && !text.isEmpty()) ear.onPartial(text);
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }

        private String first(Bundle b) {
            ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return list == null || list.isEmpty() ? null : list.get(0);
        }
    }

    // ---- listening animations --------------------------------------------------------------------

    /** Base for the two listening animations: frames of their own, about 60 a second, only while running. */
    abstract static class Animated extends Drawable implements Runnable {
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
     * A ring round the mic while listening: 2px of white at 35%, from a little inside the box's edge, growing to
     * past it and fading out every 1.4 s.
     */
    static final class Pulse extends Animated {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** The mic's radius and the ring's distance out from it, in pixels. */
        private final float radius, out, stroke;

        Pulse(float radius, float out, float stroke) {
            this.radius = radius;
            this.out = out;
            this.stroke = stroke;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        public void draw(Canvas c) {
            if (!running) return;
            Rect b = getBounds();
            float p = ((SystemClock.uptimeMillis() - start) % 1400) / 1400f;
            float e = 1f - (1f - p) * (1f - p);   // ease-out
            paint.setStrokeWidth(stroke);
            paint.setAlpha(Math.round(255 * 0.35f * 0.8f * (1f - e)));
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), (radius + out) * (0.85f + 0.5f * e), paint);
        }
    }

    /**
     * Bars rising and falling, each on its own beat (900 ms, 60 ms behind the one before); the voice sets how far.
     * The recogniser's level jumps, so each frame moves a fifth of the way to it.
     */
    static final class Wave extends Animated {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final int bars;
        private final float width, step, min, maxBase, maxSpread;
        private float target, level;

        Wave(int bars, float width, float step, float min, float maxBase, float maxSpread) {
            this.bars = bars;
            this.width = width;
            this.step = step;
            this.min = min;
            this.maxBase = maxBase;
            this.maxSpread = maxSpread;
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
            float cy = b.exactCenterY();
            long t = SystemClock.uptimeMillis() - start;
            for (int i = 0; i < bars; i++) {
                float max = maxBase + Math.round(Math.abs(Math.sin(i * 1.3)) * maxSpread);
                float h = min;
                if (running) {
                    float p = ((t + i * 60L) % 1600) / 800f;
                    float tri = p <= 1f ? p : 2f - p;
                    float ease = (float) (0.5 - 0.5 * Math.cos(Math.PI * tri));
                    h = min + (max - min) * ease * (0.3f + 0.7f * level);
                }
                float x = b.left + i * step;
                bar.set(x, cy - h / 2f, x + width, cy + h / 2f);
                c.drawRoundRect(bar, width / 2f, width / 2f, paint);
            }
        }
    }
}
