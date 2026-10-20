package io.github.deval123.nightshelf;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A request to stop cleanly: the run finishes the clip it is on, records it
 * and prints its summary. Set from a shutdown hook on SIGINT and SIGTERM.
 */
public final class StopSignal {

    private final AtomicBoolean requested = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);

    public void request() {
        requested.set(true);
    }

    public boolean requested() {
        return requested.get();
    }

    /** Called when the run is over, so a waiting shutdown hook can let the process end. */
    public void markFinished() {
        finished.countDown();
    }

    boolean isFinished() {
        return finished.getCount() == 0;
    }

    void awaitFinished() throws InterruptedException {
        finished.await();
    }
}
