package aether.director;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;

/**
 * The file transport's waker: no events anywhere. Under Wine the shim's
 * SetEvent targets a Wine event, which cannot wake a native Linux process, so
 * the shim maps the ring file with a null server event and spins for its
 * reply (vfs-shim/src/director.rs, WakeServerSpinClient). {@link #completed}
 * therefore does nothing.
 *
 * {@link #idle} is vfs-ipc's AdaptiveNotifier (rust/crates/vfs-ipc/src/notifier.rs:69-136,
 * the waker vfs-director's IpcServe::start_file_backed gives every worker,
 * ipc.rs:286-327): spin for the window after a served request
 * (VFS_RING_SPIN_US, default 400 us), then park, longer the longer the ring
 * has been quiet: 100 us, 500 us once quiet past 5 ms, 2 ms once quiet past
 * 50 ms (notifier.rs:99-109). The three park lengths are configurable.
 *
 * On Windows, parkNanos has about 15.6 ms granularity (the default timer
 * resolution rounds a short park up), so an idle file ring there answers its
 * first request after a quiet spell in up to ~16 ms. Linux honours 100 us.
 */
public final class ParkWaker implements Waker {
    public static final long DEFAULT_SPIN_NANOS = 400_000L;
    public static final long DEFAULT_FLOOR_NANOS = 100_000L;
    public static final long DEFAULT_MID_NANOS = 500_000L;
    public static final long DEFAULT_CAP_NANOS = 2_000_000L;
    private static final long MID_AFTER = 5_000_000L;
    private static final long CAP_AFTER = 50_000_000L;

    private final long spinNanos;
    private final long floorNanos;
    private final long midNanos;
    private final long capNanos;
    private final Set<Thread> parked = ConcurrentHashMap.newKeySet();

    public ParkWaker() {
        this(DEFAULT_SPIN_NANOS, DEFAULT_FLOOR_NANOS, DEFAULT_MID_NANOS, DEFAULT_CAP_NANOS);
    }

    public ParkWaker(long spinNanos, long floorNanos, long midNanos, long capNanos) {
        this.spinNanos = spinNanos;
        this.floorNanos = floorNanos;
        this.midNanos = midNanos;
        this.capNanos = capNanos;
    }

    public long spinNanos() { return spinNanos; }

    /** How long a worker quiet for {@code quietNanos} parks. */
    public long parkNanos(long quietNanos) {
        if (quietNanos > CAP_AFTER) return capNanos;
        if (quietNanos > MID_AFTER) return midNanos;
        return floorNanos;
    }

    @Override
    public void completed() {}

    @Override
    public void idle(long quietNanos) {
        if (quietNanos < spinNanos) {
            for (int i = 0; i < 64; i++) Thread.onSpinWait();
            return;
        }
        Thread t = Thread.currentThread();
        parked.add(t);
        try {
            LockSupport.parkNanos(this, parkNanos(quietNanos));
        } finally {
            parked.remove(t);
        }
    }

    @Override
    public void wakeAll() {
        for (Thread t : parked) LockSupport.unpark(t);
    }

    @Override
    public void close() {}
}
