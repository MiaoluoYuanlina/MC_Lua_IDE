package wiki.ica.xiaomiao.mcluaide.server;

import org.luaj.vm2.LuaError;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Monotonic fixed-rate schedule. Accessed under the owning execution's eventCallbacks lock. */
final class LuaIntervalTimer {
    final int intervalMs;
    private final long period, created;
    private long next, lastStarted, count, lastScheduledCount, totalSkipped;
    private boolean inFlight;

    LuaIntervalTimer(double intervalMs, long now) {
        this.intervalMs = validate(intervalMs);
        this.period = TimeUnit.MILLISECONDS.toNanos(this.intervalMs);
        this.created = this.lastStarted = now;
        this.next = now + period;
    }

    static int validate(double milliseconds) {
        if (!Double.isFinite(milliseconds) || milliseconds != Math.rint(milliseconds)
                || milliseconds < 1 || milliseconds > 3_600_000)
            throw new LuaError("定时事件间隔 intervalMs 必须是 1 到 3600000 之间的整数毫秒");
        return (int) milliseconds;
    }

    long waitNanos(long now) { return inFlight ? Long.MAX_VALUE : Math.max(0, next - now); }

    boolean claim(long now) {
        if (inFlight || now - next < 0) return false;
        inFlight = true;
        advancePast(now);
        return true;
    }

    Map<String, ?> started(int registrationId, long now) {
        long scheduledCount = Math.max(1, (now - created) / period);
        long skipped = Math.max(0, scheduledCount - lastScheduledCount - 1);
        totalSkipped += skipped;
        double scheduledMs = scheduledCount * (double) intervalMs;
        double actualMs = (now - created) / 1_000_000.0;
        Map<String, ?> data = Map.ofEntries(
                Map.entry("registrationId", registrationId), Map.entry("intervalMs", intervalMs),
                Map.entry("count", ++count), Map.entry("scheduledCount", scheduledCount),
                Map.entry("elapsedMs", scheduledMs),
                Map.entry("deltaMs", (scheduledCount - lastScheduledCount) * (double) intervalMs),
                Map.entry("actualElapsedMs", actualMs),
                Map.entry("actualDeltaMs", (now - lastStarted) / 1_000_000.0),
                Map.entry("lateMs", Math.max(0, actualMs - scheduledMs)),
                Map.entry("skippedCount", skipped), Map.entry("totalSkippedCount", totalSkipped));
        lastScheduledCount = scheduledCount;
        lastStarted = now;
        return data;
    }

    void release(long now) {
        inFlight = false;
        if (now - next >= 0) advancePast(now);
    }

    private void advancePast(long now) { next += ((now - next) / period + 1) * period; }
}
