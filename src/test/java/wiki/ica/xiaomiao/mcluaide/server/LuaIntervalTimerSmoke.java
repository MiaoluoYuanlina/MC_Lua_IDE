package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.LuaError;

import java.util.concurrent.TimeUnit;

/** Deterministic deadline/overlap/skip tests, independent of OS timer resolution and Minecraft ticks. */
public final class LuaIntervalTimerSmoke {
    private static int checks;
    public static void main(String[] args) {
        for (double invalid : new double[]{0, -1, 0.5, 1.5, 3_600_001, Double.NaN, Double.POSITIVE_INFINITY}) {
            boolean rejected = false;
            try { new LuaIntervalTimer(invalid, 0); } catch (LuaError expected) { rejected = true; }
            require(rejected, "Invalid period rejected " + invalid);
        }
        require(LuaIntervalTimer.validate(1) == 1 && LuaIntervalTimer.validate(3_600_000) == 3_600_000, "Boundary periods allowed");
        LuaIntervalTimer timer = new LuaIntervalTimer(100, 0);
        require(!timer.claim(ms(99)) && timer.waitNanos(ms(99)) == ms(1), "First occurrence waits full period");
        require(timer.claim(ms(100)), "First deadline due");
        var first = timer.started(5, ms(100));
        require(first.get("registrationId").equals(5) && first.get("intervalMs").equals(100)
                && first.get("count").equals(1L) && first.get("elapsedMs").equals(100.0)
                && first.get("deltaMs").equals(100.0), "First callback payload");
        require(!timer.claim(ms(200)) && timer.waitNanos(ms(200)) == Long.MAX_VALUE, "Sleeping callback cannot overlap or spin");
        timer.release(ms(510));
        require(timer.waitNanos(ms(510)) == ms(90) && !timer.claim(ms(599)), "Missed periods skipped, fixed cadence retained");
        require(timer.claim(ms(600)), "Resumes at next future deadline");
        var second = timer.started(5, ms(601));
        require(second.get("count").equals(2L) && second.get("deltaMs").equals(500.0)
                && second.get("elapsedMs").equals(600.0) && second.get("actualDeltaMs").equals(501.0)
                && second.get("actualElapsedMs").equals(601.0) && second.get("lateMs").equals(1.0)
                && second.get("scheduledCount").equals(6L) && second.get("skippedCount").equals(4L)
                && second.get("totalSkippedCount").equals(4L), "Fixed schedule and actual jitter are separate");
        timer.release(ms(602));
        require(timer.waitNanos(ms(602)) == ms(98), "Short callbacks do not drift cadence");
        LuaIntervalTimer saturated = new LuaIntervalTimer(10, 0);
        require(saturated.claim(ms(10)), "Capacity-test deadline claimed");
        saturated.release(ms(10)); // Runtime rejected submission; no callback started.
        require(saturated.claim(ms(20)) && saturated.started(2, ms(20)).get("count").equals(1L), "Rejected occurrence not counted or backlogged");
        LuaIntervalTimer late = new LuaIntervalTimer(1, 0);
        require(late.claim(ms(10_000)), "Long dispatcher pause admits one occurrence");
        late.started(9, ms(10_000)); late.release(ms(10_000));
        require(late.waitNanos(ms(10_000)) == ms(1), "Long pause does not enqueue missed occurrences");
        long start = Long.MAX_VALUE - ms(50);
        LuaIntervalTimer wrap = new LuaIntervalTimer(100, start);
        require(wrap.waitNanos(start) == ms(100) && !wrap.claim(start + ms(99)) && wrap.claim(start + ms(100)), "System.nanoTime signed wraparound supported");
        LuaIntervalTimer fast = new LuaIntervalTimer(5, 0), slow = new LuaIntervalTimer(20, 0);
        require(fast.claim(ms(5)) && !slow.claim(ms(5)) && slow.claim(ms(20)), "Multiple schedules independent");
        LuaIntervalTimer longRun = new LuaIntervalTimer(100, 0);
        for (int index = 1; index <= 100_000; index++) {
            long now = ms(index * 100L + 7);
            require(longRun.claim(now), "Long-run cadence due");
            var data = longRun.started(1, now);
            require(data.get("elapsedMs").equals(index * 100.0) && data.get("deltaMs").equals(100.0)
                    && data.get("lateMs").equals(7.0) && data.get("totalSkippedCount").equals(0L),
                    "Repeated dispatch jitter accumulated into logical time");
            longRun.release(now);
        }
        System.out.println("Interval timer smoke passed: " + checks + " checks");
    }
    private static long ms(long value) { return TimeUnit.MILLISECONDS.toNanos(value); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
