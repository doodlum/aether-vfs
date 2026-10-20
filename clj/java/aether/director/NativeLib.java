package aether.director;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The director's native library, aether_native (LMDB, zstd, BLAKE3, xxHash;
 * built by native/build). It ships inside the jar for every supported
 * platform, at natives/&lt;os&gt;-&lt;arch&gt;/; the one for this machine is
 * extracted once to a per-user cache, named by its content hash so versions
 * never collide, and loaded from there.
 *
 * <p>{@code -Daether.native.lib=<path>} loads that file instead (a local
 * build); {@code -Daether.native.cache=<dir>} moves the cache.
 */
public final class NativeLib {
    private NativeLib() {}

    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup LIB = SymbolLookup.libraryLookup(path(), Arena.global());

    /** A downcall to {@code name} in the library. */
    public static MethodHandle fn(String name, FunctionDescriptor fd, Linker.Option... opts) {
        return LINKER.downcallHandle(LIB.find(name).orElseThrow(
            () -> new UnsatisfiedLinkError(name + " not found in aether_native")), fd, opts);
    }

    /** "windows-x86_64", "linux-aarch64", "macos-aarch64", … */
    public static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String o = os.startsWith("windows") ? "windows"
                 : os.startsWith("mac") || os.startsWith("darwin") ? "macos"
                 : os.startsWith("linux") ? "linux" : os;
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String a = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        return o + "-" + a;
    }

    static String fileName(String platform) {
        if (platform.startsWith("windows")) return "aether_native.dll";
        if (platform.startsWith("macos")) return "libaether_native.dylib";
        return "libaether_native.so";
    }

    private static Path path() {
        String explicit = System.getProperty("aether.native.lib");
        if (explicit != null) return Path.of(explicit);
        String platform = platform();
        String name = fileName(platform);
        String resource = "natives/" + platform + "/" + name;
        byte[] bytes;
        try (InputStream in = NativeLib.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("aether_native isn't built for " + platform + " (no " + resource + ")");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path dir = cacheDir().resolve(platform + "-" + sha256(bytes).substring(0, 16));
        Path file = dir.resolve(name);
        if (Files.isRegularFile(file)) return file;
        try {
            Files.createDirectories(dir);
            // another process may be extracting the same file: write a private
            // copy, then move it into place; whoever is first wins
            Path tmp = Files.createTempFile(dir, name, ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | java.nio.file.AccessDeniedException e) {
                Files.deleteIfExists(tmp);
            }
            if (!Files.isRegularFile(file)) throw new IOException("couldn't place " + file);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("extracting aether_native to " + dir, e);
        }
    }

    private static Path cacheDir() {
        String explicit = System.getProperty("aether.native.cache");
        if (explicit != null) return Path.of(explicit);
        String home = System.getProperty("user.home");
        String p = platform();
        if (p.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null ? local : home, "aether", "natives");
        }
        if (p.startsWith("macos")) return Path.of(home, "Library", "Caches", "aether", "natives");
        String xdg = System.getenv("XDG_CACHE_HOME");
        return Path.of(xdg != null ? xdg : home + "/.cache", "aether", "natives");
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
