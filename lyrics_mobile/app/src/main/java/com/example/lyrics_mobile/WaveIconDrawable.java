package com.example.lyrics_mobile;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Static music symbol, not a microphone recording or simulated playback meter. */
final class WaveIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    WaveIconDrawable(int color) { paint.setColor(color); paint.setStrokeCap(Paint.Cap.ROUND); }
    @Override public void draw(Canvas canvas) {
        float w = getBounds().width(), h = getBounds().height();
        paint.setStrokeWidth(w / 13f);
        float[] heights = {.25f, .65f, .95f, .5f, .2f};
        for (int i = 0; i < heights.length; i++) {
            float x = getBounds().left + w * (.14f + i * .18f), y = getBounds().centerY();
            canvas.drawLine(x, y - h * heights[i] / 2, x, y + h * heights[i] / 2, paint);
        }
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
