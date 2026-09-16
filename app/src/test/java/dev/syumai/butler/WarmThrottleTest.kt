package dev.syumai.butler
import org.junit.Assert.*
import org.junit.Test
class WarmThrottleTest {
    @Test fun firstCallEverIsAllowed() {
        assertTrue(WarmThrottle.shouldWarm(now = 5_000, lastAt = 0))
    }
    @Test fun blockedWithinTheInterval() {
        assertFalse(WarmThrottle.shouldWarm(now = 65_000, lastAt = 10_000, intervalMs = 60_000))
    }
    @Test fun allowedOnceTheIntervalHasFullyElapsed() {
        assertTrue(WarmThrottle.shouldWarm(now = 70_001, lastAt = 10_000, intervalMs = 60_000))
    }
    @Test fun boundaryAtExactlyTheIntervalIsAllowed() {
        assertTrue(WarmThrottle.shouldWarm(now = 70_000, lastAt = 10_000, intervalMs = 60_000))
    }
}
