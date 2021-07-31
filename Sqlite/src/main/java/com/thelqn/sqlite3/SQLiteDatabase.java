/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.thelqn.sqlite3;


import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;

public class SQLiteDatabase {

    private final long sqliteHandle;

    private boolean isOpen;
    private boolean inTransaction;

    public long getSQLiteHandle() {
        return sqliteHandle;
    }

    /**
     * Database constructor for default location "/data/data/PackageName/files" dir
     * @param context  Application context
     * @param fileName Database file name
     * @throws SQLiteException
     */
    public SQLiteDatabase(Context context, String fileName) throws SQLiteException {
        System.loadLibrary("tmessages.34");
        sqliteHandle = opendb(fileName, getFilesDirFixed(context).getPath());
        isOpen = true;
    }

    /**
     * Database constructor for custom directory
     * @param fileName Database file name
     * @throws SQLiteException
     */
    // TODO : test app
    public SQLiteDatabase(String fileName, String filePath) throws SQLiteException {
        System.loadLibrary("sqlite");
        sqliteHandle = opendb(fileName, filePath);
        isOpen = true;
    }

    public boolean tableExists(String tableName) throws SQLiteException {
        checkOpened();
        String s = "SELECT rowid FROM sqlite_master WHERE type='table' AND name=?;";
        return executeInt(s, tableName) != null;
    }

    public SQLitePreparedStatement executeFast(String sql) throws SQLiteException {
        return new SQLitePreparedStatement(this, sql);
    }

    public Integer executeInt(String sql, Object... args) throws SQLiteException {
        checkOpened();
        SQLiteCursor cursor = queryFinalized(sql, args);
        try {
            if (!cursor.next()) {
                return null;
            }
            return cursor.intValue(0);
        } finally {
            cursor.dispose();
        }
    }

    public void explainQuery(String sql, Object... args) throws SQLiteException {
        checkOpened();
        SQLiteCursor cursor = new SQLitePreparedStatement(this, "EXPLAIN QUERY PLAN " + sql).query(args);
        while (cursor.next()) {
            int count = cursor.getColumnCount();
            StringBuilder builder = new StringBuilder();
            for (int a = 0; a < count; a++) {
                builder.append(cursor.stringValue(a)).append(", ");
            }
            Log.d("Debug", "EXPLAIN QUERY PLAN " + builder.toString());
        }
        cursor.dispose();
    }

    public SQLiteCursor queryFinalized(String sql, Object... args) throws SQLiteException {
        checkOpened();
        return new SQLitePreparedStatement(this, sql).query(args);
    }

    public void close() {
        if (isOpen) {
            try {
                commitTransaction();
                closedb(sqliteHandle);
            } catch (SQLiteException e) {
                if (BuildVars.LOGS_ENABLED) {
                    Log.e("Error", e.getMessage(), e);
                }
            }
            isOpen = false;
        }
    }

    void checkOpened() throws SQLiteException {
        if (!isOpen) {
            throw new SQLiteException("Database closed");
        }
    }

    public void finalize() throws Throwable {
        super.finalize();
        close();
    }

    public void beginTransaction() throws SQLiteException {
        if (inTransaction) {
            throw new SQLiteException("database already in transaction");
        }
        inTransaction = true;
        beginTransaction(sqliteHandle);
    }

    public void commitTransaction() {
        if (!inTransaction) {
            return;
        }
        inTransaction = false;
        commitTransaction(sqliteHandle);
    }

    private static File getFilesDirFixed(Context context) {
        for (int a = 0; a < 10; a++) {
            File path = context.getApplicationContext().getFilesDir();
            if (path != null) {
                return path;
            }
        }
        try {
            ApplicationInfo info = context.getApplicationContext().getApplicationInfo();
            File path = new File(info.dataDir, "files");
            path.mkdirs();
            return path;
        } catch (Exception e) {
            Log.e("Error", e.getMessage());
        }
        return new File(String.format("/data/data/%s/files", context.getApplicationContext().getPackageName()));
    }

    native long opendb(String fileName, String tempDir) throws SQLiteException;

    native void closedb(long sqliteHandle) throws SQLiteException;

    native void beginTransaction(long sqliteHandle);

    native void commitTransaction(long sqliteHandle);
}
