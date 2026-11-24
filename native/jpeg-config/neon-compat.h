/* Hand-written from simd/arm/neon-compat.h.in (libjpeg-turbo 2.1.5.1) for NDK r15c clang.
 * The vld1*_x2/_x3/_x4 intrinsics are not available in that compiler, so the
 * HAVE_* flags stay undefined and libjpeg-turbo uses its own fallbacks. */
#if defined(__clang__) || defined(__GNUC__)
#define BUILTIN_CLZ(x)  __builtin_clz(x)
#define BUILTIN_CLZLL(x)  __builtin_clzll(x)
#define BUILTIN_BSWAP64(x)  __builtin_bswap64(x)
#else
#error "Unknown compiler"
#endif
