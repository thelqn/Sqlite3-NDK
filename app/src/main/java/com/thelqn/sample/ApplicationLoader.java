package com.thelqn.sample;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.util.Log;

import com.thelqn.sqlite3.NativeLoader;
import com.thelqn.sqlite3.SQLiteDatabase;

import java.io.File;

public class ApplicationLoader extends Application {

    @SuppressLint("StaticFieldLeak")
    public static volatile Context applicationContext;
    public static volatile Handler applicationHandler;

    public static File getFilesDirFixed() {
        for (int a = 0; a < 10; a++) {
            File path = ApplicationLoader.applicationContext.getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = applicationContext.getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            Log.e("Error files dir fixed", e.getMessage());
        }
        return new File("/data/data/com.thelqn.sample/files");
    }

    @Override
    public void onCreate() {
        try {
            applicationContext = getApplicationContext();
        } catch (Throwable ignore) {
        }

        super.onCreate();

        if (applicationContext == null) {
            applicationContext = getApplicationContext();
        }
        applicationHandler = new Handler(applicationContext.getMainLooper());

        NativeLoader.initNativeLibs(ApplicationLoader.applicationContext);
    }
}
