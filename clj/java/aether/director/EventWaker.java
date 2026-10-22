package aether.director;

/**
 * The named-section transport's waker, as vfs-win's EventNotifier: the
 * client waits on {@code cli} for its reply and signals {@code srv} on
 * submit. A worker that served within {@code spinNanos} spins; otherwise it
 * waits on {@code srv} for 1 ms. Owns both event handles.
 */
public final class EventWaker implements Waker {
    private final long srv;
    private final long cli;
    private final long spinNanos;

    public EventWaker(long srv, long cli, long spinNanos) {
        this.srv = srv;
        this.cli = cli;
        this.spinNanos = spinNanos;
    }

    public long serverEvent() { return srv; }
    public long clientEvent() { return cli; }

    @Override
    public void completed() {
        Native.setEvent(cli);
    }

    @Override
    public void idle(long quietNanos) {
        if (quietNanos < spinNanos) {
            for (int i = 0; i < 64; i++) Thread.onSpinWait();
        } else {
            Native.waitFor(srv, 1);
        }
    }

    /** One signal: a worker in its 1 ms wait returns now; the rest within 1 ms. */
    @Override
    public void wakeAll() {
        Native.setEvent(srv);
    }

    @Override
    public void close() {
        Native.closeHandle(srv);
        Native.closeHandle(cli);
    }
}
