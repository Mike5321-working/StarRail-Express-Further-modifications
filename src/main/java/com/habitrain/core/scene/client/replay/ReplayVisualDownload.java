package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

final class ReplayVisualDownload {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayVisualDownload.class);
    private final UUID round;
    private final Map<UUID, List<VisualTape.Event>> ready = new LinkedHashMap<>(4,.75f,true);
    private UUID loading;
    private byte[] buffer;
    private int offset;
    private long retry, deadline, missingSince;
    private boolean failed;
    private CompletableFuture<List<VisualTape.Event>> decoding;
    ReplayVisualDownload(UUID round) { this.round = round; }
    List<VisualTape.Event> events(UUID subject) { return subject == null ? List.of() : ready.getOrDefault(subject, List.of()); }
    boolean need(UUID subject) {
        if (subject == null || ready.containsKey(subject) || !ClientPlayNetworking.canSend(VisualPackets.Request.TYPE)) return false;
        long now = System.nanoTime();
        if (!subject.equals(loading)) { loading = subject; buffer = null; offset = 0; retry = 0; missingSince = 0; deadline = now + TimeUnit.SECONDS.toNanos(90); failed = false; decoding = null; }
        if (failed || now > deadline) {
            LOGGER.warn("Recorded viewpoint {} unavailable for round {} ({}); falling back to replay track data",
                    subject, round, failed ? "stream error" : "timeout");
            completeFallback(subject);
            return false;
        }
        if (decoding != null) {
            if (!decoding.isDone()) return true;
            try { ready.put(subject, decoding.join()); } catch (CompletionException failure) {
                LOGGER.warn("Could not decode recorded viewpoint {} for round {}; falling back to replay track data",
                        subject, round, failure.getCause());
                ready.put(subject, List.of());
            }
            decoding = null; loading = null;
            trimCache();
            return false;
        }
        if (now >= retry) {
            ClientPlayNetworking.send(new VisualPackets.Request(round, subject, offset));
            retry = now + TimeUnit.SECONDS.toNanos(1);
        }
        return true;
    }
    private void completeFallback(UUID subject) {
        ready.put(subject, List.of());
        loading = null;
        buffer = null;
        offset = 0;
        failed = false;
        decoding = null;
        trimCache();
    }
    private void trimCache() {
        if (ready.size() > 3) ready.keySet().stream().filter(key -> !key.equals(VisualTape.WORLD)).findFirst().ifPresent(ready::remove);
    }
    void clear() {
        ready.clear();
        loading = null;
        buffer = null;
        offset = 0;
        if (decoding != null) {
            decoding.cancel(true);
            decoding = null;
        }
        failed = false;
    }

    void accept(VisualPackets.Page page) {
        if (!page.subject().equals(loading) || page.offset() != offset || decoding != null) return;
        int total = page.total();
        if (total == -1 && offset == 0) {
            long now=System.nanoTime();
            if (missingSince==0) missingSince=now;
            if (now-missingSince>=TimeUnit.SECONDS.toNanos(4)) { ready.put(loading,List.of()); loading=null; }
            return;
        }
        if (total == 0 && offset == 0) { ready.put(loading,List.of()); loading = null; return; }
        if (total < 1 || total > VisualTape.MAX_STREAM + VisualTape.PAGE || (buffer != null && buffer.length != total) || page.data().length != Math.min(VisualTape.PAGE, total-offset)) { failed = true; return; }
        if (buffer == null) buffer = new byte[total];
        System.arraycopy(page.data(), 0, buffer, offset, page.data().length); offset += page.data().length;
        retry = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(75); deadline = retry + TimeUnit.SECONDS.toNanos(30);
        if (offset == total) {
            byte[] packed = buffer; buffer = null;
            decoding = CompletableFuture.supplyAsync(() -> { try { return VisualTape.decode(packed); } catch (IOException failure) { throw new UncheckedIOException(failure); } }, net.minecraft.Util.ioPool());
        }
    }
}
