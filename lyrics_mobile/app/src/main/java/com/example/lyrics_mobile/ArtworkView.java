package com.example.lyrics_mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Decorative artwork; callers may supply layout params (normally a 64dp square). */
public final class ArtworkView extends View {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EDGE = 512;
    private static final long MAX_SOURCE_PIXELS = 32L * 1024 * 1024;
    private final String url;
    private final int accent;
    private final int background;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF target = new RectF();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap bitmap;
    private Call call;
    private FutureTask<Void> task;
    private int generation;

    private static final class Loader {
        static final OkHttpClient CLIENT = new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).build();
        static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
                    Thread thread = new Thread(runnable, "artwork-loader");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
    }

    public ArtworkView(Context context, SongInfo song, int accent, int background) {
        super(context);
        this.url = song == null ? "" : song.getArtworkUrl();
        this.accent = accent;
        this.background = background;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setClickable(false);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.round(64 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (bitmap != null || !ArtworkUrl.isSafe(url)) return;
        bitmap = Loader.CACHE.get(url);
        if (bitmap != null) { invalidate(); return; }
        final int requestGeneration = ++generation;
        final Call request = Loader.CLIENT.newCall(new Request.Builder().url(url).build());
        call = request;
        task = new FutureTask<>(() -> {
            Bitmap loaded = null;
            try (Response response = request.execute()) {
                ResponseBody body = response.body();
                if (response.isSuccessful() && body != null && body.contentLength() <= MAX_BYTES) {
                    loaded = decode(readLimited(body.byteStream()));
                }
            } catch (IOException | RuntimeException ignored) {
                // Artwork is optional; keep the honest placeholder on any failure.
            }
            final Bitmap result = loaded;
            if (result != null && !request.isCanceled()) Loader.CACHE.put(url, result);
            if (!request.isCanceled()) main.post(() -> {
                if (!isAttachedToWindow() || generation != requestGeneration || call != request) return;
                call = null;
                task = null;
                if (result != null) { bitmap = result; invalidate(); }
            });
            return null;
        });
        try {
            Loader.WORKERS.execute(task);
        } catch (RejectedExecutionException ignored) {
            request.cancel();
            call = null;
            task = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        generation++;
        if (call != null) { call.cancel(); call = null; }
        if (task != null) {
            task.cancel(true);
            Loader.WORKERS.remove(task);
            task = null;
        }
        main.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || count > MAX_BYTES - output.size()) {
                throw new IOException("Artwork exceeds limit or was cancelled");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int width = options.outWidth, height = options.outHeight;
        if (width <= 0 || height <= 0 || (long) width * height > MAX_SOURCE_PIXELS) return null;
        options.inSampleSize = 1;
        while ((width + options.inSampleSize - 1) / options.inSampleSize > MAX_EDGE
                || (height + options.inSampleSize - 1) / options.inSampleSize > MAX_EDGE) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap result = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (result != null && (result.getWidth() > MAX_EDGE || result.getHeight() > MAX_EDGE)) {
            result.recycle();
            return null;
        }
        return result;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(background);
        float width = getWidth(), height = getHeight();
        if (bitmap != null) {
            int bw = bitmap.getWidth(), bh = bitmap.getHeight();
            if (width <= 0 || height <= 0) return;
            float scale = Math.max(width / bw, height / bh);
            int cropWidth = Math.max(1, Math.min(bw, Math.round(width / scale)));
            int cropHeight = Math.max(1, Math.min(bh, Math.round(height / scale)));
            source.set((bw - cropWidth) / 2, (bh - cropHeight) / 2,
                    (bw + cropWidth) / 2, (bh + cropHeight) / 2);
            target.set(0, 0, width, height);
            paint.setAlpha(255);
            canvas.drawBitmap(bitmap, source, target, paint);
        } else {
            // Abstract geometry, not fabricated cover art or album metadata.
            float unit = Math.min(width, height);
            paint.setColor(accent);
            paint.setAlpha(75);
            canvas.drawRect(width * .25f, height * .25f, width * .75f, height * .75f, paint);
            paint.setAlpha(150);
            canvas.drawCircle(width * .5f, height * .5f, unit * .12f, paint);
        }
    }
}

/** Pure Java policy, separate from View initialization so it can be unit tested. */
final class ArtworkUrl {
    private ArtworkUrl() {}

    static boolean isSafe(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("mzstatic.com") || host.endsWith(".mzstatic.com");
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
