/* Hand-written from jconfigint.h.in for libjpeg-turbo 2.1.5.1 (Android 2.3 / 32-bit ARM). */
#define BUILD  "filmlut"
#undef inline
#define INLINE  __inline__ __attribute__((always_inline))
/* Only used by SIMD dispatch / TurboJPEG, neither of which is built. Android 2.3 has no ELF TLS. */
#define THREAD_LOCAL
#define PACKAGE_NAME  "libjpeg-turbo"
#define VERSION  "2.1.5.1"
#define SIZEOF_SIZE_T  4
#define HAVE_BUILTIN_CTZL

#if defined(__has_attribute)
#if __has_attribute(fallthrough)
#define FALLTHROUGH  __attribute__((fallthrough));
#else
#define FALLTHROUGH
#endif
#else
#define FALLTHROUGH
#endif
