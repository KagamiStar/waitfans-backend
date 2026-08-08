package com.waitfans.backend.component.danmu;

import lombok.extern.slf4j.Slf4j;

import javax.websocket.Session;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Serializes BasicRemote sends for one websocket session; the five-second watchdog closes the session but cannot interrupt a blocked container write. */
@Slf4j
final class DanmuSessionSender {
    private final Session session;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Runnable onBroken;
    private boolean draining;
    private boolean closeAfterDrain;
    private boolean closed;

    DanmuSessionSender(Session session, Executor executor, ScheduledExecutorService scheduler, Runnable onBroken) {
        this.session = session;
        this.executor = executor;
        this.scheduler = scheduler;
        this.onBroken = onBroken;
    }

    synchronized boolean enqueue(String message, boolean closeAfter) {
        if (closed || closeAfterDrain || !queue.offer(message)) return false;
        if (closeAfter) closeAfterDrain = true;
        if (!draining) {
            draining = true;
            executor.execute(this::drain);
        }
        return true;
    }

    synchronized void stop() {
        closed = true;
        queue.clear();
    }

    private void drain() {
        while (true) {
            String message;
            boolean shouldClose = false;
            synchronized (this) {
                if (closed) return;
                message = queue.poll();
                if (message == null) {
                    draining = false;
                    shouldClose = closeAfterDrain;
                    if (shouldClose) closed = true;
                }
            }
            if (message == null) {
                if (shouldClose) onBroken.run();
                return;
            }
            ScheduledFuture<?> timeout = scheduler.schedule(this::fail, 5, TimeUnit.SECONDS);
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException | RuntimeException exception) {
                log.warn("Danmu websocket send failed", exception);
                fail();
                return;
            } finally {
                timeout.cancel(false);
            }
            synchronized (this) {
                if (closed) return;
            }
        }
    }

    private void fail() {
        boolean notify;
        synchronized (this) {
            notify = !closed;
            closed = true;
            queue.clear();
        }
        if (notify) onBroken.run();
    }
}
