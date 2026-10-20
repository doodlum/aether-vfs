package aether.director;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The block store's codecs, from aether_native: zstd (one frame per block),
 * xxh3-64 (a stored record's checksum) and BLAKE3 (a block's identity, and
 * key derivation for digests).
 *
 * <p>Calls that take microseconds are critical downcalls, which may be given
 * heap segments (byte arrays) directly. Compression can take a millisecond,
 * so it's an ordinary downcall, and heap input is first copied to native
 * memory: a critical call holds off the GC for as long as it runs.
 */
public final class Codec {
    private Codec() {}

    private static final Linker.Option HEAP = Linker.Option.critical(true);

    private static final MethodHandle CREATE_CCTX = NativeLib.fn("ZSTD_createCCtx", FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle CREATE_DCTX = NativeLib.fn("ZSTD_createDCtx", FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle COMPRESS = NativeLib.fn("ZSTD_compressCCtx",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle DECOMPRESS = NativeLib.fn("ZSTD_decompressDCtx",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);
    private static final MethodHandle IS_ERROR = NativeLib.fn("ZSTD_isError",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG), Linker.Option.critical(false));
    private static final MethodHandle ERROR_NAME = NativeLib.fn("ZSTD_getErrorName",
        FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle BOUND = NativeLib.fn("ZSTD_compressBound",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG), Linker.Option.critical(false));
    private static final MethodHandle XXH3 = NativeLib.fn("XXH3_64bits",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);
    private static final MethodHandle XXH64 = NativeLib.fn("XXH64",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG), HEAP);
    private static final MethodHandle XXH64_CREATE = NativeLib.fn("XXH64_createState",
        FunctionDescriptor.of(JAVA_LONG));
    private static final MethodHandle XXH64_FREE = NativeLib.fn("XXH64_freeState",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    private static final MethodHandle XXH64_RESET = NativeLib.fn("XXH64_reset",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG), Linker.Option.critical(false));
    private static final MethodHandle XXH64_UPDATE = NativeLib.fn("XXH64_update",
        FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);
    private static final MethodHandle XXH64_DIGEST = NativeLib.fn("XXH64_digest",
        FunctionDescriptor.of(JAVA_LONG, JAVA_LONG), Linker.Option.critical(false));
    private static final MethodHandle BLAKE3 = NativeLib.fn("aether_blake3",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);
    private static final MethodHandle BLAKE3_EACH = NativeLib.fn("aether_blake3_128_each",
        FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle DERIVE_NEW = NativeLib.fn("aether_blake3_derive_new",
        FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    private static final MethodHandle UPDATE = NativeLib.fn("aether_blake3_update",
        FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);
    private static final MethodHandle FINISH = NativeLib.fn("aether_blake3_finish",
        FunctionDescriptor.ofVoid(JAVA_LONG, ADDRESS, JAVA_LONG), HEAP);

    /** zstd failed: a corrupt frame, or a destination too small. */
    public static final class CodecException extends RuntimeException {
        CodecException(String m) { super(m); }
    }

    // A thread's zstd contexts and its native staging buffers (grown on demand).
    private static final class Ctx {
        final long cctx, dctx;
        MemorySegment in = MemorySegment.NULL, out = MemorySegment.NULL;
        Ctx() {
            try {
                cctx = (long) CREATE_CCTX.invokeExact();
                dctx = (long) CREATE_DCTX.invokeExact();
            } catch (Throwable t) { throw Lmdb.rethrow(t); }
        }
        MemorySegment in(long n) {
            if (in.byteSize() < n) in = Arena.ofAuto().allocate(Math.max(n, 1 << 16), 16);
            return in;
        }
        MemorySegment out(long n) {
            if (out.byteSize() < n) out = Arena.ofAuto().allocate(Math.max(n, 1 << 16), 16);
            return out;
        }
    }
    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    private static long zcheck(long r) {
        try {
            if ((int) IS_ERROR.invokeExact(r) != 0) {
                MemorySegment name = (MemorySegment) ERROR_NAME.invokeExact(r);
                throw new CodecException("zstd: " + name.reinterpret(256).getString(0));
            }
        } catch (CodecException e) { throw e; }
        catch (Throwable t) { throw Lmdb.rethrow(t); }
        return r;
    }

    public static long compressBound(long n) {
        try { return (long) BOUND.invokeExact(n); } catch (Throwable t) { throw Lmdb.rethrow(t); }
    }

    /**
     * `src` as one zstd frame at `level`, as a new byte array (the frame
     * alone). The caller compares its size with the input's to decide whether
     * to store it.
     */
    public static byte[] compress(MemorySegment src, int level) {
        Ctx c = CTX.get();
        long n = src.byteSize();
        MemorySegment in = src;
        if (!src.isNative()) {
            in = c.in(n);
            MemorySegment.copy(src, 0, in, 0, n);
        }
        long cap = compressBound(n);
        MemorySegment out = c.out(cap);
        long r;
        try { r = (long) COMPRESS.invokeExact(c.cctx, out, cap, in, n, level); }
        catch (Throwable t) { throw Lmdb.rethrow(t); }
        zcheck(r);
        return out.asSlice(0, r).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    }

    /** Decompresses the frame `src` into `dst` (exactly its size); returns the bytes written. */
    public static long decompress(MemorySegment dst, MemorySegment src) {
        Ctx c = CTX.get();
        long r;
        try { r = (long) DECOMPRESS.invokeExact(c.dctx, dst, dst.byteSize(), src, src.byteSize()); }
        catch (Throwable t) { throw Lmdb.rethrow(t); }
        return zcheck(r);
    }

    /** xxh3-64 of `src`. */
    public static long xxh3(MemorySegment src) {
        try { return (long) XXH3.invokeExact(src, src.byteSize()); } catch (Throwable t) { throw Lmdb.rethrow(t); }
    }

    /** xxHash64 of `src` with `seed` (Wabbajack's and the downloaders' file hash uses seed 0). */
    public static long xxh64(MemorySegment src, long seed) {
        try { return (long) XXH64.invokeExact(src, src.byteSize(), seed); } catch (Throwable t) { throw Lmdb.rethrow(t); }
    }

    /** A streaming xxHash64: feed it in order, then {@link Xxh64#digest}; {@link Xxh64#close} frees it. */
    public static final class Xxh64 implements AutoCloseable {
        private long state;
        public Xxh64(long seed) {
            try {
                state = (long) XXH64_CREATE.invokeExact();
                if (state == 0) throw new OutOfMemoryError("xxh64 state");
                int rc = (int) XXH64_RESET.invokeExact(state, seed);
            } catch (Throwable t) { throw Lmdb.rethrow(t); }
        }
        public Xxh64 update(MemorySegment src) {
            try { int rc = (int) XXH64_UPDATE.invokeExact(state, src, src.byteSize()); } catch (Throwable t) { throw Lmdb.rethrow(t); }
            return this;
        }
        public Xxh64 update(byte[] src, int off, int len) {
            return update(MemorySegment.ofArray(src).asSlice(off, len));
        }
        public long digest() {
            try { return (long) XXH64_DIGEST.invokeExact(state); } catch (Throwable t) { throw Lmdb.rethrow(t); }
        }
        @Override public void close() {
            if (state != 0) {
                try { int rc = (int) XXH64_FREE.invokeExact(state); } catch (Throwable t) { throw Lmdb.rethrow(t); }
                state = 0;
            }
        }
    }

    /** The first `n` bytes of BLAKE3(src). */
    public static byte[] blake3(MemorySegment src, int n) {
        byte[] out = new byte[n];
        try { BLAKE3.invokeExact(src, src.byteSize(), MemorySegment.ofArray(out), (long) n); }
        catch (Throwable t) { throw Lmdb.rethrow(t); }
        return out;
    }

    /**
     * BLAKE3-128 of each `piece`-byte piece of `src` (the last may be
     * shorter), 16 bytes each, in order: a write's block hashes.
     */
    public static byte[] blake3Blocks(MemorySegment src, long piece) {
        long len = src.byteSize();
        long n = len == 0 ? 0 : (len + piece - 1) / piece;
        byte[] out = new byte[(int) (n * 16)];
        if (n == 0) return out;
        Ctx c = CTX.get();
        MemorySegment in = src;
        if (!src.isNative()) {
            in = c.in(len);
            MemorySegment.copy(src, 0, in, 0, len);
        }
        MemorySegment o = c.out(out.length);
        try { BLAKE3_EACH.invokeExact(in, n, piece, len - (n - 1) * piece, o); }
        catch (Throwable t) { throw Lmdb.rethrow(t); }
        MemorySegment.copy(o, 0, MemorySegment.ofArray(out), 0, out.length);
        return out;
    }

    /** A BLAKE3 hasher in key-derivation mode; feed it, then {@link Deriver#finish}. */
    public static final class Deriver {
        private long h;
        Deriver(long h) { this.h = h; }
        public Deriver update(MemorySegment src) {
            try { UPDATE.invokeExact(h, src, src.byteSize()); } catch (Throwable t) { throw Lmdb.rethrow(t); }
            return this;
        }
        public Deriver update(byte[] src) { return update(MemorySegment.ofArray(src)); }
        /** `n` bytes of output; the hasher is freed. */
        public byte[] finish(int n) {
            byte[] out = new byte[n];
            try { FINISH.invokeExact(h, MemorySegment.ofArray(out), (long) n); } catch (Throwable t) { throw Lmdb.rethrow(t); }
            h = 0;
            return out;
        }
    }

    public static Deriver derive(String context) {
        try (Arena a = Arena.ofConfined()) {
            long h = (long) DERIVE_NEW.invokeExact(a.allocateFrom(context, StandardCharsets.UTF_8));
            if (h == 0) throw new OutOfMemoryError("blake3 hasher");
            return new Deriver(h);
        } catch (Throwable t) { throw Lmdb.rethrow(t); }
    }
}
