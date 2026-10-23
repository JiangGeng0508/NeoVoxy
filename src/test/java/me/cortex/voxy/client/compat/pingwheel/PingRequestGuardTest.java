package me.cortex.voxy.client.compat.pingwheel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PingRequestGuardTest {
    @Test void laterOperationIncludingNormalHitInvalidatesOldCompletion() {
        var guard = new PingRequestGuard();
        long old = guard.begin();
        assertTrue(guard.start(old));
        long newer = guard.begin();
        assertFalse(guard.current(old));
        assertFalse(guard.claimSend(old));
        assertFalse(guard.start(newer), "one bounded worker, no queued requests");
        guard.workerDone();
        assertTrue(guard.start(newer));
        assertTrue(guard.claimSend(newer));
        assertFalse(guard.claimSend(newer), "LOD and fallback callbacks share a single send claim");
        guard.workerDone();
    }

    @Test void worldChangeReloadOrConfigCancellationRejectsQueuedCallback() {
        var guard = new PingRequestGuard();
        long token = guard.begin();
        assertTrue(guard.start(token));
        guard.invalidate();
        guard.workerDone();
        assertFalse(guard.claimSend(token));
        assertFalse(guard.start(token));
        long fresh = guard.begin();
        assertTrue(guard.start(fresh));
        assertTrue(guard.claimSend(fresh));
        guard.workerDone();
    }
}
