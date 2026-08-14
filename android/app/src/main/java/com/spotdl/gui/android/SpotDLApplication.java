package com.spotdl.gui.android;

import android.app.Application;
import android.util.Log;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SpotDLApplication extends Application {
    private static final String TAG = "SpotDLApplication";
    private static volatile boolean engineReady;
    private static volatile String engineError;

    @Override public void onCreate() {
        super.onCreate();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(this);
                FFmpeg.getInstance().init(this);
                engineReady = true;
            } catch (Exception error) {
                engineError = error.getLocalizedMessage();
                Log.e(TAG, "Engine initialization failed", error);
            } finally {
                executor.shutdown();
            }
        });
    }

    public static boolean isEngineReady() { return engineReady; }
    public static String getEngineError() { return engineError; }
}
