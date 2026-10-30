package com.example.lyrics_mobile;

import java.io.InterruptedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.Call;
import okhttp3.Response;

/** Each operation owns its calls; closing a screen cancels the actual HTTP I/O. */
public final class OperationScope {
    private volatile boolean cancelled;
    public interface Progress { void update(String message); }
    private Progress progress;
    public OperationScope onProgress(Progress listener) { progress = listener; return this; }
    public void progress(String message) { if (!cancelled && progress != null) progress.update(message); }
    public final java.util.List<String> diagnostics = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void record(String provider, String model, String code, long elapsedMs) {
        diagnostics.add(provider + " | " + (model == null ? "default" : model.replaceAll("[^A-Za-z0-9._:-]", "")) + " | " + code.replaceAll("[^A-Za-z0-9_:-]", "") + " | " + elapsedMs + "ms");
    }
    private final Set<Call> calls = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    public boolean isCancelled() { return cancelled; }
    public void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    public Response execute(Call call) throws java.io.IOException {
        check();
        calls.add(call);
        if (cancelled) call.cancel();
        // Keep the call registered while the caller reads the response body, too.
        return call.execute();
    }
    public void cancel() {
        cancelled = true;
        for (Call call : calls) call.cancel();
        calls.clear();
    }
}
