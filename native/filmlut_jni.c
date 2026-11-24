/* JNI bridge. The Java class is chosen at build time (-DFILMLUT_JNI_CLASS="pkg/Native"):
 *
 *   static native String apply(String in, String out, byte[] lut, int quality); // null = OK
 *   static native int    progress();                                            // 0..1000
 *   static native void   cancel();
 *   static native int[]  preview(String path, int maxW, int maxH);  // [w, h, argb...] or null
 *   static native boolean lutArgb(int[] src, int[] dst, int offset, int count, byte[] lut);
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "filmlut.h"

#ifndef FILMLUT_JNI_CLASS
#error "define FILMLUT_JNI_CLASS, e.g. -DFILMLUT_JNI_CLASS=\"dev/filmlut/app/Native\""
#endif

static unsigned char *copy_bytes(JNIEnv *env, jbyteArray a, jsize *len)
{
    unsigned char *buf;
    *len = (*env)->GetArrayLength(env, a);
    buf = (unsigned char *)malloc(*len > 0 ? *len : 1);
    if (buf) (*env)->GetByteArrayRegion(env, a, 0, *len, (jbyte *)buf);
    return buf;
}

static jstring j_apply(JNIEnv *env, jclass cls, jstring jin, jstring jout, jbyteArray jlut, jint quality)
{
    char msg[256];
    const char *in = (*env)->GetStringUTFChars(env, jin, NULL);
    const char *out = (*env)->GetStringUTFChars(env, jout, NULL);
    jsize len;
    unsigned char *lut = copy_bytes(env, jlut, &len);
    int rc;
    (void)cls;
    if (!lut) {
        rc = 9; snprintf(msg, sizeof msg, "out of memory");
    } else {
        rc = filmlut_apply(in, out, lut, (size_t)len, quality, msg, sizeof msg);
        free(lut);
    }
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return rc == 0 ? NULL : (*env)->NewStringUTF(env, msg);
}

static jint j_progress(JNIEnv *env, jclass cls) { (void)env; (void)cls; return filmlut_progress(); }
static jstring j_stats(JNIEnv *env, jclass cls) { (void)cls; return (*env)->NewStringUTF(env, filmlut_stats()); }
static void j_preview_abort(JNIEnv *env, jclass cls) { (void)env; (void)cls; filmlut_preview_abort(); }
static void j_cancel(JNIEnv *env, jclass cls) { (void)env; (void)cls; filmlut_cancel(); }
static void j_set_grain(JNIEnv *env, jclass cls, jint g) { (void)env; (void)cls; filmlut_set_grain(g); }
static void j_set_halation(JNIEnv *env, jclass cls, jint h) { (void)env; (void)cls; filmlut_set_halation(h); }

static jintArray j_preview(JNIEnv *env, jclass cls, jstring jpath, jint max_w, jint max_h)
{
    char msg[256];
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int w = 0, h = 0;
    int *px = filmlut_preview_abortable(path, max_w, max_h, &w, &h, msg, sizeof msg);
    jintArray res = NULL;
    (void)cls;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!px) return NULL;
    res = (*env)->NewIntArray(env, 2 + w * h);
    if (res) {
        jint wh[2];
        wh[0] = w; wh[1] = h;
        (*env)->SetIntArrayRegion(env, res, 0, 2, wh);
        (*env)->SetIntArrayRegion(env, res, 2, w * h, (const jint *)px);
    }
    free(px);
    return res;
}

static jboolean j_lut_argb(JNIEnv *env, jclass cls, jintArray jsrc, jintArray jdst, jint offset, jint count, jbyteArray jlut)
{
    jsize len;
    unsigned char *lutbuf;
    lut3d lut;
    jint *src, *dst;
    jboolean ok = JNI_FALSE;
    (void)cls;
    if (offset < 0 || count < 0 ||
        offset + count > (*env)->GetArrayLength(env, jsrc) || offset + count > (*env)->GetArrayLength(env, jdst))
        return JNI_FALSE;
    lutbuf = copy_bytes(env, jlut, &len);
    if (!lutbuf) return JNI_FALSE;
    if (filmlut_lut_init(&lut, lutbuf, (size_t)len)) {
        src = (*env)->GetIntArrayElements(env, jsrc, NULL);
        dst = (*env)->GetIntArrayElements(env, jdst, NULL);
        if (src && dst) {
            filmlut_lut_argb(&lut, (const int *)src + offset, (int *)dst + offset, (unsigned int)count);
            ok = JNI_TRUE;
        }
        if (src) (*env)->ReleaseIntArrayElements(env, jsrc, src, JNI_ABORT);
        if (dst) (*env)->ReleaseIntArrayElements(env, jdst, dst, 0);
    }
    free(lutbuf);
    return ok;
}

static jboolean j_preview_halation(JNIEnv *env, jclass cls, jstring jpath, jintArray jpx, jint offset, jint w, jint h, jint strength)
{
    const char *path;
    jint *px;
    int ok = 0;
    (void)cls;
    if (offset < 0 || w <= 0 || h <= 0 || offset + w * h > (*env)->GetArrayLength(env, jpx)) return JNI_FALSE;
    path = (*env)->GetStringUTFChars(env, jpath, NULL);
    px = (*env)->GetIntArrayElements(env, jpx, NULL);
    if (path && px) ok = filmlut_preview_halation(path, (int *)px + offset, w, h, strength);
    if (px) (*env)->ReleaseIntArrayElements(env, jpx, px, ok ? 0 : JNI_ABORT);
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean j_is_adobe(JNIEnv *env, jclass cls, jstring jpath)
{
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int r = path ? filmlut_is_adobe(path) : 0;
    (void)cls;
    if (path) (*env)->ReleaseStringUTFChars(env, jpath, path);
    return r ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray j_adobe_lut(JNIEnv *env, jclass cls, jbyteArray jlut)
{
    jsize len;
    unsigned char *lut = copy_bytes(env, jlut, &len), *out;
    jbyteArray res = NULL;
    (void)cls;
    if (!lut) return NULL;
    out = filmlut_lut_adobe(lut, (size_t)len);
    free(lut);
    if (!out) return NULL;
    if ((res = (*env)->NewByteArray(env, len)) != NULL) (*env)->SetByteArrayRegion(env, res, 0, len, (const jbyte *)out);
    free(out);
    return res;
}

static const JNINativeMethod methods[] = {
    { "isAdobe",  "(Ljava/lang/String;)Z",                                       (void *)j_is_adobe },
    { "adobeLut", "([B)[B",                                                      (void *)j_adobe_lut },
    { "apply",    "(Ljava/lang/String;Ljava/lang/String;[BI)Ljava/lang/String;", (void *)j_apply },
    { "progress", "()I",                                                         (void *)j_progress },
    { "stats",    "()Ljava/lang/String;",                                        (void *)j_stats },
    { "cancel",   "()V",                                                         (void *)j_cancel },
    { "setGrain", "(I)V",                                                        (void *)j_set_grain },
    { "setHalation", "(I)V",                                                     (void *)j_set_halation },
    { "preview",  "(Ljava/lang/String;II)[I",                                    (void *)j_preview },
    { "previewAbort", "()V",                                                     (void *)j_preview_abort },
    { "previewHalation", "(Ljava/lang/String;[IIIII)Z",                          (void *)j_preview_halation },
    { "lutArgb",  "([I[III[B)Z",                                                 (void *)j_lut_argb },
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env;
    jclass cls;
    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) return -1;
    cls = (*env)->FindClass(env, FILMLUT_JNI_CLASS);
    if (!cls) return -1;
    if ((*env)->RegisterNatives(env, cls, methods, sizeof methods / sizeof methods[0]) != 0) return -1;
    return JNI_VERSION_1_4;
}
