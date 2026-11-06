package com.gathin.tvhome;

import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * cover_flow (with poster_carousel): the tiles beside the focused one turn sideways toward it, smaller and darker,
 * and pack in closer the further out they are. Every tile is placed from its distance to the middle of the row on
 * each scroll frame, so the tiles turn as the row moves rather than jumping at the end of it. Only the item views'
 * rotation, translation and scale change: no layers, no blur, nothing redrawn but the row.
 */
final class CoverFlow {
    /**
     * How far a side tile turns, how much smaller it is, how much smaller again each one further out, and the most
     * black over a tile far out. Taken from the prototype (z 140 in front and -160 / -30 behind at a 1800px
     * perspective), measured on screen; the turn opened from its 62deg to 45deg on the TV, so the next app's icon
     * reads before you reach it.
     */
    static final float SIDE_ANGLE = 45f, SIDE_SCALE = 0.85f, FAR_SCALE = 0.015f, MAX_DIM = 0.78f;
    /**
     * The first neighbour's centre sits this many tile widths out; each one after it this many more. A tile turned
     * less shows wider, so at 45deg the step grew from the prototype's 0.29: at that it covered the icon of the
     * tile behind it.
     */
    static final float FIRST_STEP = 0.82f, NEXT_STEP = 0.38f;
    /** The perspective the turn is seen with, as a share of the screen width (CSS perspective 1800px on 1920). */
    static final float PERSPECTIVE = 0.94f;

    private CoverFlow() { }

    /** The row's own drawing order, nearest the middle last (on top); rebuilt at the first child of each pass. */
    private static int[] order = new int[0];

    /** home_widgets: tiles before the focused one fade out behind the widget column instead of crowding it. */
    private static boolean fadeBefore;

    static void attach(RecyclerView grid, boolean fadeTilesBefore) {
        fadeBefore = fadeTilesBefore;
        DisplayMetrics dm = grid.getResources().getDisplayMetrics();
        // setCameraDistance takes pixels at the screen's dpi, with 72 of Skia's camera units to the inch.
        float camera = dm.widthPixels * PERSPECTIVE * dm.densityDpi / 72f;
        grid.setChildDrawingOrderCallback((count, i) -> {
            if (i == 0 || order.length != count) order = drawingOrder(grid, count);
            return i < order.length ? order[i] : i;
        });
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                place(rv, camera);
            }
        });
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> place(grid, camera));
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View v) {
                place(grid, camera);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View v) {
            }
        });
    }

    /**
     * A tile's offset from the focused place, in item widths: 0 there, 1 for the next one. The focused tile sits at
     * the row's left padding (MainActivity): the middle of the screen, or just right of the widgets.
     */
    private static float offset(RecyclerView grid, View child) {
        int w = child.getWidth();
        return w == 0 ? 0f : (child.getLeft() - grid.getPaddingLeft()) / (float) w;
    }

    static void place(RecyclerView grid, float camera) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View item = grid.getChildAt(i);
            View card = item.findViewById(R.id.card);
            View dim = item.findViewById(R.id.dim);
            if (card == null || dim == null || item.getWidth() == 0) continue;
            float t = offset(grid, item);
            float a = Math.abs(t), s = Math.signum(t), near = Math.min(a, 1f), far = Math.max(0f, a - 1f);
            float tile = card.getWidth();
            float want = near * FIRST_STEP * tile + far * NEXT_STEP * tile;
            // Under a 3D turn a rounded clip, a faded view and text all get expensive to draw on a TV's CPU: the clip
            // goes stencil, a view alpha needs an offscreen layer, and glyphs are drawn as paths. So only the
            // middle tile clips its corners and shows its name, and the black comes from the colour's own alpha.
            boolean middle = a < 0.01f;
            card.setClipToOutline(middle);
            View label = item.findViewById(R.id.label);
            if (label != null) label.setVisibility(a < 0.5f ? View.VISIBLE : View.INVISIBLE);
            item.setCameraDistance(camera);
            item.setTranslationX(s * want - t * item.getWidth());
            // Positive rotationY turns a view's right edge away from the viewer. A tile right of the middle turns the
            // other way: its outer edge comes forward and its inner edge recedes under the tile before it, so each
            // tile further out tucks beneath the one nearer the middle (drawingOrder draws that one on top).
            item.setRotationY(-s * near * SIDE_ANGLE);
            float scale = 1f - (1f - SIDE_SCALE) * near - FAR_SCALE * far;
            item.setScaleX(scale);
            item.setScaleY(scale);
            dim.setAlpha(1f);
            dim.getBackground().mutate().setAlpha(Math.round(255 * Math.min(MAX_DIM, near * 0.5f + far * 0.08f)));
            // A view alpha costs a layer, so only the one tile on its way out or back pays it; at 0 nothing is drawn.
            item.setAlpha(fadeBefore && t < 0 ? Math.max(0f, 1f + t * 1.4f) : 1f);
        }
        grid.invalidate();
    }

    /** Children sorted farthest from the middle first, so the nearer tiles overlap the ones behind them. */
    private static int[] drawingOrder(RecyclerView grid, int count) {
        int[] idx = new int[count];
        float[] dist = new float[count];
        for (int i = 0; i < count; i++) {
            idx[i] = i;
            dist[i] = i < grid.getChildCount() ? Math.abs(offset(grid, grid.getChildAt(i))) : 0f;
        }
        // Insertion sort: a handful of tiles on screen.
        for (int i = 1; i < count; i++) {
            int k = idx[i];
            int j = i - 1;
            while (j >= 0 && dist[idx[j]] < dist[k]) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = k;
        }
        return idx;
    }
}
