/*
 * The director's native library: LMDB, zstd, BLAKE3 and xxHash, linked into
 * one shared library and called through Java's FFM API. Their own functions
 * are called directly; this file adds only what FFM can't do well: BLAKE3's
 * hasher is a large struct whose layout is the library's business, so it
 * lives behind these calls.
 */
#include <stdlib.h>
#include <string.h>

#include "blake3.h"

/* On Windows nothing is marked dllexport, so the linker exports every global
 * (LMDB's functions aren't marked either); one dllexport would hide them. */
#if defined(_WIN32)
#  define AETHER_API
#else
#  define AETHER_API __attribute__((visibility("default")))
#endif

/* BLAKE3 of `len` bytes at `in`, its first `out_len` bytes to `out`. */
AETHER_API void aether_blake3(const void *in, size_t len, unsigned char *out, size_t out_len) {
    blake3_hasher h;
    blake3_hasher_init(&h);
    blake3_hasher_update(&h, in, len);
    blake3_hasher_finalize(&h, out, out_len);
}

/*
 * BLAKE3 of `n` consecutive pieces of `piece` bytes (the last `last` bytes),
 * each to 16 bytes of `out`: the block store's hash of every block of a
 * write, in one call.
 */
AETHER_API void aether_blake3_128_each(const unsigned char *in, size_t n, size_t piece, size_t last,
                                       unsigned char *out) {
    for (size_t i = 0; i < n; i++) {
        aether_blake3(in + i * piece, i + 1 == n ? last : piece, out + i * 16, 16);
    }
}

/* A BLAKE3 hasher in key-derivation mode for `context` (NUL-terminated). */
AETHER_API blake3_hasher *aether_blake3_derive_new(const char *context) {
    blake3_hasher *h = malloc(sizeof *h);
    if (h) blake3_hasher_init_derive_key(h, context);
    return h;
}

AETHER_API void aether_blake3_update(blake3_hasher *h, const void *in, size_t len) {
    blake3_hasher_update(h, in, len);
}

/* Writes `out_len` bytes of output and frees the hasher. */
AETHER_API void aether_blake3_finish(blake3_hasher *h, unsigned char *out, size_t out_len) {
    blake3_hasher_finalize(h, out, out_len);
    free(h);
}
