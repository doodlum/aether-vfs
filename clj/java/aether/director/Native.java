package aether.director;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The few primitives the ring needs that Clojure can't call efficiently:
 * VarHandle atomics and MethodHandle downcalls are signature-polymorphic, which
 * Clojure only reaches through reflection. Everything else is in Clojure.
 *
 * Win32 handles are passed around as longs (their address).
 */
public final class Native {
    private Native() {}

    // ------------------------------------------------------------------ atomics

    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();

    /** Acquire load of the int at {@code off}. */
    public static int getAcquire(MemorySegment s, long off) {
        return (int) INT.getAcquire(s, off);
    }

    /** Release store of the int at {@code off}. */
    public static void setRelease(MemorySegment s, long off, int v) {
        INT.setRelease(s, off, v);
    }

    /** Compare-and-set (volatile semantics, at least acquire on success). */
    public static boolean cas(MemorySegment s, long off, int expected, int value) {
        return INT.compareAndSet(s, off, expected, value);
    }

    // ------------------------------------------------------------------ kernel32
    // Looked up on first use (a holder class), so loading this class off
    // Windows doesn't fail.

    private static final class K32 {
        static final Linker LINKER = Linker.nativeLinker();
        static final SymbolLookup LIB = SymbolLookup.libraryLookup("kernel32", Arena.global());
        static final MemoryLayout CALL_STATE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("GetLastError"));
        static final Linker.Option CAPTURE = Linker.Option.captureCallState("GetLastError");

        static MethodHandle h(String name, FunctionDescriptor fd, Linker.Option... opts) {
            return LINKER.downcallHandle(LIB.find(name).orElseThrow(), fd, opts);
        }

        // HANDLE CreateFileMappingW(HANDLE, LPSECURITY_ATTRIBUTES, DWORD protect, DWORD sizeHigh, DWORD sizeLow, LPCWSTR)
        static final MethodHandle CREATE_FILE_MAPPING = h("CreateFileMappingW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), CAPTURE);
        // LPVOID MapViewOfFile(HANDLE, DWORD access, DWORD offHigh, DWORD offLow, SIZE_T bytes)
        static final MethodHandle MAP_VIEW = h("MapViewOfFile",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ValueLayout.JAVA_LONG), CAPTURE);
        static final MethodHandle UNMAP_VIEW = h("UnmapViewOfFile",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle CLOSE_HANDLE = h("CloseHandle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
        // HANDLE CreateEventW(LPSECURITY_ATTRIBUTES, BOOL manualReset, BOOL initialState, LPCWSTR)
        static final MethodHandle CREATE_EVENT = h("CreateEventW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), CAPTURE);
        // SetEvent returns at once: a critical call skips the thread-state transition.
        static final MethodHandle SET_EVENT = h("SetEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS), Linker.Option.critical(false));
        // WaitForSingleObject blocks, so it must not be critical.
        static final MethodHandle WAIT = h("WaitForSingleObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    }

    private static MemorySegment wide(Arena a, String s) {
        return a.allocateFrom(s, StandardCharsets.UTF_16LE);   // NUL-terminated
    }

    private static RuntimeException lastError(String what, MemorySegment state) {
        int code = state.get(JAVA_INT, K32.LAST_ERROR);
        return new IllegalStateException(what + " failed: Win32 error " + code);
    }

    private static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

    /** A named, page-file-backed section, mapped whole. Returns {mappingHandle, view}. */
    public static Section createSection(String name, long size) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(K32.CALL_STATE);
            MemorySegment h = (MemorySegment) K32.CREATE_FILE_MAPPING.invokeExact(
                state, INVALID_HANDLE_VALUE, MemorySegment.NULL, 0x04 /* PAGE_READWRITE */,
                (int) (size >>> 32), (int) size, wide(a, name));
            if (h.address() == 0) throw lastError("CreateFileMappingW " + name, state);
            MemorySegment view = (MemorySegment) K32.MAP_VIEW.invokeExact(
                state, h, 0xF001F /* FILE_MAP_ALL_ACCESS */, 0, 0, size);
            if (view.address() == 0) throw lastError("MapViewOfFile " + name, state);
            return new Section(h.address(), view.reinterpret(size));
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    public record Section(long handle, MemorySegment view) {
        public void close() {
            try {
                int ignored = (int) K32.UNMAP_VIEW.invokeExact(MemorySegment.ofAddress(view.address()));
                closeHandle(handle);
            } catch (Throwable t) {
                throw sneaky(t);
            }
        }
    }

    /** A named auto-reset event, initially unsignalled. */
    public static long createEvent(String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment state = a.allocate(K32.CALL_STATE);
            MemorySegment h = (MemorySegment) K32.CREATE_EVENT.invokeExact(
                state, MemorySegment.NULL, 0, 0, wide(a, name));
            if (h.address() == 0) throw lastError("CreateEventW " + name, state);
            return h.address();
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    public static void setEvent(long h) {
        try {
            int ignored = (int) K32.SET_EVENT.invokeExact(MemorySegment.ofAddress(h));
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /** WaitForSingleObject; returns its result (0 signalled, 0x102 timeout). */
    public static int waitFor(long h, int millis) {
        try {
            return (int) K32.WAIT.invokeExact(MemorySegment.ofAddress(h), millis);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    public static void closeHandle(long h) {
        try {
            int ignored = (int) K32.CLOSE_HANDLE.invokeExact(MemorySegment.ofAddress(h));
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
