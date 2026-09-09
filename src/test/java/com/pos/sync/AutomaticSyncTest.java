package com.pos.sync;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class AutomaticSyncTest {
    private void set(SyncManager manager, String name, Object value) throws Exception {
        var field = SyncManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(manager, value);
    }

    private SyncManager manager(Queue<Runnable> tasks, AtomicBoolean busy) throws Exception {
        SyncManager manager = mock(SyncManager.class, CALLS_REAL_METHODS);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; })
                .when(scheduler).execute(any(Runnable.class));
        set(manager, "scheduler", scheduler);
        set(manager, "isShuttingDown", new AtomicBoolean());
        set(manager, "isOutboundSyncing", busy);
        set(manager, "outboundRequested", new AtomicBoolean());
        set(manager, "outboundTaskQueued", new AtomicBoolean());
        return manager;
    }

    @Test
    public void burstsCoalesceAndSaleDuringUploadGetsFollowUp() throws Exception {
        Queue<Runnable> tasks = new ArrayDeque<>();
        SyncManager manager = manager(tasks, new AtomicBoolean());
        AtomicInteger runs = new AtomicInteger();
        doAnswer(call -> {
            if (runs.incrementAndGet() == 1) manager.triggerOutboundSync();
            return SyncResult.empty(SyncDirection.OUTBOUND);
        }).when(manager).performOutboundSync();
        for (int i = 0; i < 100; i++) manager.triggerOutboundSync();
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(2, runs.get());
        assertTrue(tasks.isEmpty());
    }

    @Test
    public void manualUploadDoesNotCauseOverlappingWorkerOrLoseRequest() throws Exception {
        Queue<Runnable> tasks = new ArrayDeque<>();
        AtomicBoolean busy = new AtomicBoolean(true);
        SyncManager manager = manager(tasks, busy);
        doReturn(SyncResult.empty(SyncDirection.OUTBOUND)).when(manager).performOutboundSync();
        manager.triggerOutboundSync();
        tasks.remove().run();
        verify(manager, never()).performOutboundSync();
        var field = SyncManager.class.getDeclaredField("outboundRequested");
        field.setAccessible(true);
        assertTrue(((AtomicBoolean) field.get(manager)).get());
        busy.set(false);
        manager.triggerOutboundSync();
        tasks.remove().run();
        verify(manager).performOutboundSync();
    }

    @Test
    public void failedWorkerCanBeRetriedByNextTrigger() throws Exception {
        Queue<Runnable> tasks = new ArrayDeque<>();
        SyncManager manager = manager(tasks, new AtomicBoolean());
        doThrow(new IllegalStateException("temporary failure"))
                .doReturn(SyncResult.empty(SyncDirection.OUTBOUND)).when(manager).performOutboundSync();
        manager.triggerOutboundSync();
        tasks.remove().run();
        manager.triggerOutboundSync();
        tasks.remove().run();
        verify(manager, times(2)).performOutboundSync();
    }
}
