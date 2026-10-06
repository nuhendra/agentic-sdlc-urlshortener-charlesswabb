package com.schwab.agentic.engine;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The safe-stop (kill switch) control. Trips either manually (an operator aborts) or
 * automatically when the run exhausts its error budget. Once tripped, the engine stops
 * scheduling new stages, preserves state, and rolls back.
 */
public final class SafeStopController {

    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>();

    /**
     * Trips the kill switch (idempotent; the first reason wins).
     *
     * @param why the reason for stopping
     */
    public void trip(String why) {
        if (tripped.compareAndSet(false, true)) {
            reason.set(why);
        }
    }

    /** @return whether the kill switch has been tripped */
    public boolean tripped() {
        return tripped.get();
    }

    /** @return the reason the switch was tripped, or {@code null} if not tripped */
    public String reason() {
        return reason.get();
    }
}
