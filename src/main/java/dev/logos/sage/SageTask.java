package dev.logos.sage;

/**
 * One question in flight. Cancelling before the worker starts skips it; after, it interrupts the
 * worker, which abandons the HTTP request. The thread is only interrupted while it still runs this
 * question, never the next one the pool hands it.
 */
public final class SageTask {
    public final int id;
    private Thread thread;
    private boolean cancelled, finished;

    public SageTask(int id) {
        this.id = id;
    }

    /** Called by the worker first; false when the question was cancelled before it began. */
    public synchronized boolean start() {
        if (cancelled) return false;
        thread = Thread.currentThread();
        return true;
    }

    /** Called by the worker last, on its own thread. */
    public synchronized void finish() {
        finished = true;
        thread = null;
        Thread.interrupted();
    }

    public synchronized void cancel() {
        cancelled = true;
        if (thread != null && !finished) thread.interrupt();
    }

    public synchronized boolean cancelled() {
        return cancelled;
    }
}
