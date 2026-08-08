package com.waitfans.backend.component.danmu;

import org.junit.jupiter.api.Test;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DanmuSessionSenderTest {
    @Test
    void serializesMessagesAndClosesWhenQueueIsFull() throws Exception {
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            DanmuSessionSender serial = new DanmuSessionSender(session, Runnable::run, scheduler, () -> { });
            assertTrue(serial.enqueue("first", false));
            assertTrue(serial.enqueue("second", false));
            org.mockito.InOrder order = inOrder(remote);
            order.verify(remote).sendText("first");
            order.verify(remote).sendText("second");

            DanmuSessionSender bounded = new DanmuSessionSender(session, command -> { }, scheduler, () -> { });
            for (int index = 0; index < 100; index++) assertTrue(bounded.enqueue("queued-" + index, false));
            assertFalse(bounded.enqueue("overflow", false));
            bounded.stop();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void sendFailureNotifiesCleanupOnce() throws Exception {
        Session session = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(session.getBasicRemote()).thenReturn(remote);
        org.mockito.Mockito.doThrow(new RuntimeException("broken")).when(remote).sendText(anyString());
        AtomicInteger cleanup = new AtomicInteger();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            DanmuSessionSender sender = new DanmuSessionSender(session, Runnable::run, scheduler, cleanup::incrementAndGet);
            assertTrue(sender.enqueue("broken", false));
            verify(remote).sendText("broken");
            org.junit.jupiter.api.Assertions.assertEquals(1, cleanup.get());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
