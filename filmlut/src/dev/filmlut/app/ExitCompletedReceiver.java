package dev.filmlut.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ExitCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // The camera is leaving app mode: stop any conversion (it removes its .TMP) and end the process.
        try {
            Native.cancel();
        } catch (Throwable ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
