package aether.director;

/**
 * How the ring's workers wait for work and tell a client its reply is ready.
 * Called per request per worker, so it is an interface (a primitive,
 * non-boxing call from Clojure), not a protocol.
 *
 * Wakers are advisory: correctness rests on the ring's slot atomics, which
 * both ends re-check in a loop. A missed wake costs latency, never a request.
 */
public interface Waker {
    /** After a CAS to COMPLETED. Windows sections: SetEvent on the client event. */
    void completed();

    /**
     * Nothing was SUBMITTED. {@code quietNanos} is how long since this worker
     * last served a request: spin briefly while recent, otherwise wait.
     */
    void idle(long quietNanos);

    /** On stop: wake idle workers so they see the stop flag. */
    void wakeAll();

    /** Releases what the waker owns (event handles). */
    void close();

    /** Wakes nothing: for a ring no other process shares (the warm-up's). */
    Waker NONE = new Waker() {
        public void completed() {}
        public void idle(long quietNanos) {}
        public void wakeAll() {}
        public void close() {}
    };
}
