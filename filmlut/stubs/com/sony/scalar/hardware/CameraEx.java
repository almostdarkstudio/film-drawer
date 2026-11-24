package com.sony.scalar.hardware;

import android.hardware.Camera;

import java.util.List;

/**
 * COMPILE-ONLY stub. Signatures match OpenMemories-Framework/stubs (CameraEx.j and
 * CameraEx$ParametersModifier.j); the camera's real classes are used at run time.
 * Never packaged into the APK.
 */
public class CameraEx {
    public static class OpenOptions {
    }

    /** Sony's typed view over Camera.Parameters (Creative Style etc.). */
    public static class ParametersModifier {
        public String getColorMode() { throw new RuntimeException("stub"); }
        public void setColorMode(String mode) { throw new RuntimeException("stub"); }
        public List getSupportedColorModes() { throw new RuntimeException("stub"); }
        public int getContrast() { throw new RuntimeException("stub"); }
        public void setContrast(int v) { throw new RuntimeException("stub"); }
        public int getSaturation() { throw new RuntimeException("stub"); }
        public void setSaturation(int v) { throw new RuntimeException("stub"); }
        public int getSharpness() { throw new RuntimeException("stub"); }       // -3..3 (PARAMS.TXT)
        public void setSharpness(int v) { throw new RuntimeException("stub"); }
        public String getImageSize() { throw new RuntimeException("stub"); }
        public void setImageSize(String size) { throw new RuntimeException("stub"); }
        public List getSupportedImageSizes() { throw new RuntimeException("stub"); }
        public void setISOSensitivity(int iso) { throw new RuntimeException("stub"); }          // 0 = Auto
        public String getPictureStorageFormat() { throw new RuntimeException("stub"); }   // jpeg, raw, rawjpeg
        public void setPictureStorageFormat(String fmt) { throw new RuntimeException("stub"); }
        public List getSupportedPictureStorageFormats() { throw new RuntimeException("stub"); }
        public String getDROMode() { throw new RuntimeException("stub"); }
        public void setDROMode(String mode) { throw new RuntimeException("stub"); }
        public int getDROLevel() { throw new RuntimeException("stub"); }
        public void setDROLevel(int level) { throw new RuntimeException("stub"); }
        public List getSupportedDROModes() { throw new RuntimeException("stub"); }
    }

    public static CameraEx open(int cameraId, OpenOptions options) {
        throw new RuntimeException("stub");
    }

    public Camera getNormalCamera() {
        throw new RuntimeException("stub");
    }

    public Camera.Parameters createEmptyParameters() {
        throw new RuntimeException("stub");
    }

    public ParametersModifier createParametersModifier(Camera.Parameters params) {
        throw new RuntimeException("stub");
    }

    public void incrementAperture() { throw new RuntimeException("stub"); }

    public void decrementAperture() { throw new RuntimeException("stub"); }

    public void incrementShutterSpeed() { throw new RuntimeException("stub"); }

    public void decrementShutterSpeed() { throw new RuntimeException("stub"); }

    public void cancelTakePicture() {
        throw new RuntimeException("stub");
    }

    public void release() {
        throw new RuntimeException("stub");
    }
}
