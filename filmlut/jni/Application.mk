# Android 2.3 (API 9 headers are the oldest in NDK r15c for ARM; the camera is API 10).
APP_PLATFORM := android-9
# The a7R II reports armeabi-v7a; armeabi is kept as a fallback.
APP_ABI := armeabi armeabi-v7a
APP_OPTIM := release
