package org.example.Utils;

import java.util.concurrent.TimeUnit;

public class TimeForCancellation {
    private final long cancelNs;     // Tc: time cancellation is signaled
    private final long quiescentNs;  // Tq: time request-owned workers reach 0
    private final long returnNs;     // Tr: time method returns stats
    private final long timeToStopMs;

    public TimeForCancellation(long cancelNs, long quiescentNs, long returnNs) {
        this.cancelNs = cancelNs;
        this.quiescentNs = quiescentNs;
        this.returnNs = returnNs;

        this.timeToStopMs = (cancelNs == 0 || quiescentNs == 0 || quiescentNs < cancelNs)
                ? -1
                : TimeUnit.NANOSECONDS.toMillis(quiescentNs - cancelNs);
    }

    public long getTimeToStopMs() {
        return timeToStopMs;
    }
}
