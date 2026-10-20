package aether.director;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * LMDB 1.0 (in aether_native), as thin static calls. Handles (env, txn,
 * cursor) are their native addresses as longs, which every 64-bit ABI we
 * ship passes the same way as a pointer.
 *
 * <p>Keys and values go in as byte arrays or segments. A key is copied into a
 * per-thread native buffer; a value is written with MDB_RESERVE, straight into
 * the map. {@link #get} returns a view of the map itself, valid until the
 * transaction ends, so a read copies nothing it doesn't keep.
 *
 * <p>Failures throw {@link LmdbException}; "not found" (and "exists", for
 * NOOVERWRITE puts) are results.
 */
public final class Lmdb {
    private Lmdb() {}

    // env flags
    public static final int NOSUBDIR = 0x4000, NOSYNC = 0x10000, RDONLY = 0x20000, NOMETASYNC = 0x40000,
                            WRITEMAP = 0x80000, NOTLS = 0x200000, NORDAHEAD = 0x800000;
    // dbi flags
    public static final int CREATE = 0x40000;
    // put flags
    public static final int NOOVERWRITE = 0x10, RESERVE = 0x10000, APPEND = 0x20000;
    public static final int CP_COMPACT = 0x01;
    // cursor ops (MDB_cursor_op)
    public static final int FIRST = 0, GET_CURRENT = 4, LAST = 6, NEXT = 8, PREV = 12, SET = 15, SET_KEY = 16, SET_RANGE = 17;
    // return codes
    public static final int KEYEXIST = -30799, NOTFOUND = -30798, MAP_FULL = -30792, MAP_RESIZED = -30785,
                            READERS_FULL = -30790, TXN_FULL = -30788;

    private static final Linker.Option CRITICAL = Linker.Option.critical(false);

    private static final MethodHandle VERSION = NativeLib.fn("mdb_version",
        FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle STRERROR = NativeLib.fn("mdb_strerror",
        FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle ENV_CREATE = NativeLib.fn("mdb_env_create",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle ENV_OPEN = NativeLib.fn("mdb_env_open",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle ENV_CLOSE = NativeLib.fn("mdb_env_close",
        FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle ENV_SYNC = NativeLib.fn("mdb_env_sync",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle ENV_COPY2 = NativeLib.fn("mdb_env_copy2",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle SET_MAPSIZE = NativeLib.fn("mdb_env_set_mapsize",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle SET_PAGESIZE = NativeLib.fn("mdb_env_set_pagesize",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SET_MAXREADERS = NativeLib.fn("mdb_env_set_maxreaders",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle SET_MAXDBS = NativeLib.fn("mdb_env_set_maxdbs",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT));
    private static final MethodHandle MAXKEYSIZE = NativeLib.fn("mdb_env_get_maxkeysize",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle READER_CHECK = NativeLib.fn("mdb_reader_check",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle TXN_BEGIN = NativeLib.fn("mdb_txn_begin",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle TXN_COMMIT = NativeLib.fn("mdb_txn_commit",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle TXN_ABORT = NativeLib.fn("mdb_txn_abort",
        FunctionDescriptor.ofVoid(JAVA_LONG));
    private static final MethodHandle TXN_RESET = NativeLib.fn("mdb_txn_reset",
        FunctionDescriptor.ofVoid(JAVA_LONG), CRITICAL);
    private static final MethodHandle TXN_RENEW = NativeLib.fn("mdb_txn_renew",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG), CRITICAL);
    private static final MethodHandle DBI_OPEN = NativeLib.fn("mdb_dbi_open",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG));
    private static final MethodHandle GET = NativeLib.fn("mdb_get",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), CRITICAL);
    private static final MethodHandle PUT = NativeLib.fn("mdb_put",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT), CRITICAL);
    private static final MethodHandle DEL = NativeLib.fn("mdb_del",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), CRITICAL);
    private static final MethodHandle CURSOR_OPEN = NativeLib.fn("mdb_cursor_open",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG), CRITICAL);
    private static final MethodHandle CURSOR_CLOSE = NativeLib.fn("mdb_cursor_close",
        FunctionDescriptor.ofVoid(JAVA_LONG), CRITICAL);
    private static final MethodHandle CURSOR_GET = NativeLib.fn("mdb_cursor_get",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT), CRITICAL);
    private static final MethodHandle CURSOR_DEL = NativeLib.fn("mdb_cursor_del",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT), CRITICAL);

    /** An LMDB failure: {@link #code} is LMDB's (or the OS's) error number. */
    public static final class LmdbException extends RuntimeException {
        public final int code;
        LmdbException(int code, String what) {
            super(what + ": " + strerror(code) + " (" + code + ")");
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ scratch

    private static final int KEY_BUF = 1 << 16;

    /** Per-thread native memory: two MDB_vals, an out-pointer, key bytes. */
    private static final class Scratch {
        final MemorySegment vals, out, key;
        Scratch() {
            Arena a = Arena.ofAuto();
            vals = a.allocate(32, 8);   // key MDB_val at 0, data MDB_val at 16
            out = a.allocate(8, 8);
            key = a.allocate(KEY_BUF, 8);
        }
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** Points `vals`' MDB_val at `off` at `seg`, copying it into `buf` if it's on the heap. */
    private static void setVal(MemorySegment vals, long off, MemorySegment seg, MemorySegment buf) {
        long n = seg.byteSize();
        long addr;
        if (seg.isNative()) {
            addr = seg.address();
        } else {
            if (n > buf.byteSize()) throw new IllegalArgumentException("key of " + n + " bytes");
            MemorySegment.copy(seg, 0, buf, 0, n);
            addr = buf.address();
        }
        vals.set(JAVA_LONG, off, n);
        vals.set(JAVA_LONG, off + 8, addr);
    }

    private static MemorySegment data(MemorySegment vals, long off) {
        long n = vals.get(JAVA_LONG, off);
        return MemorySegment.ofAddress(vals.get(JAVA_LONG, off + 8)).reinterpret(n);
    }

    private static int check(int rc, String what) {
        if (rc != 0) throw new LmdbException(rc, what);
        return rc;
    }

    // ------------------------------------------------------------------ env

    public static String strerror(int code) {
        try {
            return ((MemorySegment) STRERROR.invokeExact(code)).reinterpret(1024).getString(0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    /** "1.0.2" */
    public static String version() {
        try {
            MemorySegment s = (MemorySegment) VERSION.invokeExact(0L, 0L, 0L);
            return s.reinterpret(256).getString(0);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static long envCreate() {
        Scratch s = SCRATCH.get();
        try {
            check((int) ENV_CREATE.invokeExact(s.out.address()), "mdb_env_create");
        } catch (Throwable t) { throw rethrow(t); }
        return s.out.get(JAVA_LONG, 0);
    }

    public static void setMapsize(long env, long bytes) {
        try { check((int) SET_MAPSIZE.invokeExact(env, bytes), "mdb_env_set_mapsize"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static void setPagesize(long env, int bytes) {
        try { check((int) SET_PAGESIZE.invokeExact(env, bytes), "mdb_env_set_pagesize"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static void setMaxreaders(long env, int n) {
        try { check((int) SET_MAXREADERS.invokeExact(env, n), "mdb_env_set_maxreaders"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static void setMaxdbs(long env, int n) {
        try { check((int) SET_MAXDBS.invokeExact(env, n), "mdb_env_set_maxdbs"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static void envOpen(long env, String path, int flags, int mode) {
        try (Arena a = Arena.ofConfined()) {
            check((int) ENV_OPEN.invokeExact(env, a.allocateFrom(path, StandardCharsets.UTF_8), flags, mode),
                  "mdb_env_open " + path);
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static void envClose(long env) {
        try { ENV_CLOSE.invokeExact(env); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Flushes the data to disk (with `force`, even when opened NOSYNC/MAPASYNC). */
    public static void envSync(long env, boolean force) {
        try { check((int) ENV_SYNC.invokeExact(env, force ? 1 : 0), "mdb_env_sync"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** Copies the environment to `path` (a directory), compacting with CP_COMPACT. */
    public static void envCopy(long env, String path, int flags) {
        try (Arena a = Arena.ofConfined()) {
            check((int) ENV_COPY2.invokeExact(env, a.allocateFrom(path, StandardCharsets.UTF_8), flags), "mdb_env_copy2");
        } catch (Throwable t) { throw rethrow(t); }
    }

    public static int maxKeySize(long env) {
        try { return (int) MAXKEYSIZE.invokeExact(env); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Clears reader slots left by dead processes; returns how many. */
    public static int readerCheck(long env) {
        Scratch s = SCRATCH.get();
        try {
            check((int) READER_CHECK.invokeExact(env, s.out.address()), "mdb_reader_check");
        } catch (Throwable t) { throw rethrow(t); }
        return s.out.get(JAVA_INT, 0);
    }

    // ------------------------------------------------------------------ txns

    /** A transaction (RDONLY in `flags` for a read one); `parent` 0 for none. */
    public static long txnBegin(long env, long parent, int flags) {
        Scratch s = SCRATCH.get();
        try {
            check((int) TXN_BEGIN.invokeExact(env, parent, flags, s.out.address()), "mdb_txn_begin");
        } catch (Throwable t) { throw rethrow(t); }
        return s.out.get(JAVA_LONG, 0);
    }

    public static void txnCommit(long txn) {
        try { check((int) TXN_COMMIT.invokeExact(txn), "mdb_txn_commit"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static void txnAbort(long txn) {
        try { TXN_ABORT.invokeExact(txn); } catch (Throwable t) { throw rethrow(t); }
    }

    /** Releases a read transaction's snapshot, keeping it for {@link #txnRenew}. */
    public static void txnReset(long txn) {
        try { TXN_RESET.invokeExact(txn); } catch (Throwable t) { throw rethrow(t); }
    }

    public static void txnRenew(long txn) {
        try { check((int) TXN_RENEW.invokeExact(txn), "mdb_txn_renew"); }
        catch (Throwable t) { throw rethrow(t); }
    }

    /** A named database (CREATE in `flags` to create it, in a write txn). */
    public static int dbiOpen(long txn, String name, int flags) {
        Scratch s = SCRATCH.get();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = name == null ? MemorySegment.NULL : a.allocateFrom(name, StandardCharsets.UTF_8);
            check((int) DBI_OPEN.invokeExact(txn, n, flags, s.out.address()), "mdb_dbi_open " + name);
        } catch (Throwable t) { throw rethrow(t); }
        return s.out.get(JAVA_INT, 0);
    }

    // ------------------------------------------------------------------ data

    /** The value under `key`, as a view of the map (valid until the txn ends), or null. */
    public static MemorySegment get(long txn, int dbi, MemorySegment key) {
        Scratch s = SCRATCH.get();
        setVal(s.vals, 0, key, s.key);
        int rc;
        try { rc = (int) GET.invokeExact(txn, dbi, s.vals.address(), s.vals.address() + 16); }
        catch (Throwable t) { throw rethrow(t); }
        if (rc == NOTFOUND) return null;
        check(rc, "mdb_get");
        return data(s.vals, 16);
    }

    public static MemorySegment get(long txn, int dbi, byte[] key) {
        return get(txn, dbi, MemorySegment.ofArray(key));
    }

    /**
     * Room for a `size`-byte value under `key`, in the map, for the caller to
     * fill before the txn's next write; null when NOOVERWRITE is in `flags` and
     * the key exists.
     */
    public static MemorySegment reserve(long txn, int dbi, MemorySegment key, long size, int flags) {
        Scratch s = SCRATCH.get();
        setVal(s.vals, 0, key, s.key);
        s.vals.set(JAVA_LONG, 16, size);
        s.vals.set(JAVA_LONG, 24, 0L);
        int rc;
        try { rc = (int) PUT.invokeExact(txn, dbi, s.vals.address(), s.vals.address() + 16, flags | RESERVE); }
        catch (Throwable t) { throw rethrow(t); }
        if (rc == KEYEXIST) return null;
        check(rc, "mdb_put");
        return data(s.vals, 16);
    }

    /** Puts `value` under `key`; false when NOOVERWRITE is in `flags` and the key exists. */
    public static boolean put(long txn, int dbi, MemorySegment key, MemorySegment value, int flags) {
        MemorySegment dst = reserve(txn, dbi, key, value.byteSize(), flags);
        if (dst == null) return false;
        MemorySegment.copy(value, 0, dst, 0, value.byteSize());
        return true;
    }

    public static boolean put(long txn, int dbi, byte[] key, byte[] value, int flags) {
        return put(txn, dbi, MemorySegment.ofArray(key), MemorySegment.ofArray(value), flags);
    }

    /** Deletes `key`; false if it wasn't there. */
    public static boolean del(long txn, int dbi, MemorySegment key) {
        Scratch s = SCRATCH.get();
        setVal(s.vals, 0, key, s.key);
        int rc;
        try { rc = (int) DEL.invokeExact(txn, dbi, s.vals.address(), 0L); }
        catch (Throwable t) { throw rethrow(t); }
        if (rc == NOTFOUND) return false;
        check(rc, "mdb_del");
        return true;
    }

    public static boolean del(long txn, int dbi, byte[] key) {
        return del(txn, dbi, MemorySegment.ofArray(key));
    }

    // ------------------------------------------------------------------ cursors

    /**
     * A cursor over one database in one txn. Each positioning call returns
     * whether there is an entry; {@link #key} and {@link #val} are then views
     * of it in the map.
     */
    public static final class Cursor implements AutoCloseable {
        private final long ptr;
        private final MemorySegment vals, keyBuf;

        Cursor(long ptr) {
            this.ptr = ptr;
            Arena a = Arena.ofAuto();
            this.vals = a.allocate(32, 8);
            this.keyBuf = a.allocate(KEY_BUF, 8);
        }

        private boolean op(int op) {
            int rc;
            try { rc = (int) CURSOR_GET.invokeExact(ptr, vals.address(), vals.address() + 16, op); }
            catch (Throwable t) { throw rethrow(t); }
            if (rc == NOTFOUND) return false;
            check(rc, "mdb_cursor_get");
            return true;
        }

        public boolean first() { return op(FIRST); }
        public boolean last() { return op(LAST); }
        public boolean next() { return op(NEXT); }
        public boolean prev() { return op(PREV); }

        /** Positions at the first key >= `key`. */
        public boolean seek(MemorySegment key) {
            setVal(vals, 0, key, keyBuf);
            return op(SET_RANGE);
        }

        public boolean seek(byte[] key) { return seek(MemorySegment.ofArray(key)); }

        public MemorySegment key() { return data(vals, 0); }
        public MemorySegment val() { return data(vals, 16); }
        public byte[] keyBytes() { return key().toArray(ValueLayout.JAVA_BYTE); }

        /** Deletes the entry at the cursor. */
        public void del() {
            try { check((int) CURSOR_DEL.invokeExact(ptr, 0), "mdb_cursor_del"); }
            catch (Throwable t) { throw rethrow(t); }
        }

        @Override public void close() {
            try { CURSOR_CLOSE.invokeExact(ptr); } catch (Throwable t) { throw rethrow(t); }
        }
    }

    public static Cursor cursor(long txn, int dbi) {
        Scratch s = SCRATCH.get();
        try {
            check((int) CURSOR_OPEN.invokeExact(txn, dbi, s.out.address()), "mdb_cursor_open");
        } catch (Throwable t) { throw rethrow(t); }
        return new Cursor(s.out.get(JAVA_LONG, 0));
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
