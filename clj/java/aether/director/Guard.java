package aether.director;

import clojure.lang.IFn;

/**
 * Calls a request handler without boxing its result. A Clojure {@code try}
 * always yields an Object, so guarding the dispatch in Clojure would allocate
 * a Long per request; a failed handler must still complete its slot, or the
 * game thread waiting on it spins forever.
 */
public final class Guard {
    private Guard() {}

    /** {@code f} is a Clojure fn of [Object ^long] returning ^long; on any
     *  Throwable, returns {@code failure}. */
    public static long call(IFn.OLL f, Object state, long slot, long failure) {
        try {
            return f.invokePrim(state, slot);
        } catch (Throwable t) {
            return failure;
        }
    }
}
