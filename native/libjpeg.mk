# Static libjpeg-turbo 2.1.5.1 for ndk-build. Include from an app's jni/Android.mk with
# FILMLUT_NATIVE set to this directory.
#   armeabi      : plain C (jsimd_none.c)
#   armeabi-v7a  : + NEON intrinsics, enabled at run time only if /proc/cpuinfo lists "neon"
#                  (aarch32/jsimd.c is built without -mfpu=neon, so it is safe on any ARMv7).
# Sources are relative to LOCAL_PATH so object paths stay short (Windows 260-char limit).
JT := $(FILMLUT_NATIVE)/../vendor/libjpeg-turbo
LOCAL_PATH := $(JT)
include $(CLEAR_VARS)
LOCAL_MODULE := jpeg-turbo
LOCAL_SRC_FILES := \
  jcapimin.c jcapistd.c jccoefct.c jccolor.c jcdctmgr.c jchuff.c \
  jcicc.c jcinit.c jcmainct.c jcmarker.c jcmaster.c jcomapi.c jcparam.c \
  jcphuff.c jcprepct.c jcsample.c jctrans.c jdapimin.c jdapistd.c jdatadst.c \
  jdatasrc.c jdcoefct.c jdcolor.c jddctmgr.c jdhuff.c jdicc.c jdinput.c \
  jdmainct.c jdmarker.c jdmaster.c jdmerge.c jdphuff.c jdpostct.c jdsample.c \
  jdtrans.c jerror.c jfdctflt.c jfdctfst.c jfdctint.c jidctflt.c jidctfst.c \
  jidctint.c jidctred.c jquant1.c jquant2.c jutils.c jmemmgr.c jmemnobs.c
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
  # ".neon" suffix: ndk-build compiles just these files with -mfpu=neon.
  LOCAL_SRC_FILES += \
    simd/arm/jcgray-neon.c.neon simd/arm/jcphuff-neon.c.neon simd/arm/jcsample-neon.c.neon \
    simd/arm/jdmerge-neon.c.neon simd/arm/jdsample-neon.c.neon simd/arm/jfdctfst-neon.c.neon \
    simd/arm/jidctred-neon.c.neon simd/arm/jquanti-neon.c.neon simd/arm/jccolor-neon.c.neon \
    simd/arm/jidctint-neon.c.neon simd/arm/jidctfst-neon.c.neon simd/arm/aarch32/jchuff-neon.c.neon \
    simd/arm/jdcolor-neon.c.neon simd/arm/jfdctint-neon.c.neon \
    simd/arm/aarch32/jsimd.c
  LOCAL_CFLAGS_SIMD := -DWITH_SIMD -DNEON_INTRINSICS
else
  LOCAL_SRC_FILES += jsimd_none.c
  LOCAL_CFLAGS_SIMD :=
endif
LOCAL_C_INCLUDES := $(FILMLUT_NATIVE)/jpeg-config $(JT) $(JT)/simd/arm
LOCAL_EXPORT_C_INCLUDES := $(FILMLUT_NATIVE)/jpeg-config $(JT)
LOCAL_CFLAGS := -O2 -DNO_GETENV -DNO_PUTENV -Wno-attributes $(LOCAL_CFLAGS_SIMD)
LOCAL_ARM_MODE := arm
include $(BUILD_STATIC_LIBRARY)
