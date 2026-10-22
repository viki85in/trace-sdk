package org.util.trace.spring;

import org.springframework.context.SmartLifecycle;
import org.util.trace.core.TraceRuntime;

/** Starts the exporter with the context and flushes it on close. Stops after all other lifecycle beans. */
final class TraceSdkLifecycle implements SmartLifecycle {

    /** Low phase: started first, stopped last, so spans from other beans' shutdown are still sent. */
    static final int PHASE = Integer.MIN_VALUE + 1000;

    private final TraceRuntime runtime;
    private volatile boolean running;

    TraceSdkLifecycle(TraceRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void start() {
        runtime.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        runtime.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
