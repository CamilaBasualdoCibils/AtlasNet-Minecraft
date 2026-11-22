package net.atlasnet.minecraft.sdk.plugin;

/** Schedules work on the local server execution context. */
public interface Scheduler {
    /** Schedules one invocation after the given number of local ticks. */
    Task schedule(long delayTicks, Runnable action);

    interface Task {
        /** Returns whether the pending invocation was cancelled before it started. */
        boolean cancel();
    }
}
