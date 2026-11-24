# Shared app native build: libjpeg-turbo + libfilmlut.so. Include from an app's jni/Android.mk
# after setting FILMLUT_JNI_CLASS (e.g. dev/filmlut/app/Native).
include $(FILMLUT_NATIVE)/libjpeg.mk

LOCAL_PATH := $(FILMLUT_NATIVE)
include $(CLEAR_VARS)
LOCAL_MODULE := filmlut
LOCAL_SRC_FILES := filmlut.c filmlut_jni.c
LOCAL_C_INCLUDES := $(FILMLUT_NATIVE)
LOCAL_STATIC_LIBRARIES := jpeg-turbo
LOCAL_CFLAGS := -O2 -Wall -DFILMLUT_JNI_CLASS=\"$(FILMLUT_JNI_CLASS)\"
LOCAL_ARM_MODE := arm
include $(BUILD_SHARED_LIBRARY)
