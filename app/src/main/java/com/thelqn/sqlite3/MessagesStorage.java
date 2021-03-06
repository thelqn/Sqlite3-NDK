/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package com.thelqn.sqlite3;

import android.util.Log;

import com.thelqn.sqlite3.model.EventListener;
import com.thelqn.sqlite3.model.Message;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class MessagesStorage {

    private DispatchQueue storageQueue = new DispatchQueue("storageQueue");
    private SQLiteDatabase database;
    private File cacheFile;
    private File walCacheFile;
    private File shmCacheFile;

    private byte[] secretPBytes = null;

    private CountDownLatch openSync = new CountDownLatch(1);

    private static volatile MessagesStorage Instance = null;
    private final static int LAST_DB_VERSION = 1;

    public static MessagesStorage getInstance(int num) {
        MessagesStorage localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesStorage.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MessagesStorage(num);
                }
            }
        }
        return localInstance;
    }

    public MessagesStorage(int instance) {
        storageQueue.postRunnable(() -> openDatabase(1));
    }

    public SQLiteDatabase getDatabase() {
        return database;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    public long getDatabaseSize() {
        long size = 0;
        if (cacheFile != null) {
            size += cacheFile.length();
        }
        if (shmCacheFile != null) {
            size += shmCacheFile.length();
        }
        return size;
    }

    public void openDatabase(int openTries) {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        filesDir = new File(filesDir, "account/");
        filesDir.mkdirs();

        cacheFile = new File(filesDir, "cache4.db");
        walCacheFile = new File(filesDir, "cache4.db-wal");
        shmCacheFile = new File(filesDir, "cache4.db-shm");

        boolean createTable = false;
        // this will be delete your database
        // cacheFile.delete();

        if (!cacheFile.exists()) {
            createTable = true;
        }
        try {

            database = new SQLiteDatabase(cacheFile.getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();


            if (createTable) {

                database.executeFast("CREATE TABLE messages(uid INTEGER PRIMARY KEY AUTOINCREMENT, 'from' TEXT, 'message' TEXT);").stepThis().dispose();

                //version
                database.executeFast("PRAGMA user_version = " + LAST_DB_VERSION).stepThis().dispose();
            } else {
                int version = database.executeInt("PRAGMA user_version");
                if (BuildVars.LOGS_ENABLED) {
                    Log.d("Debug", "current db version = " + version);
                }
                if (version == 0) {
                    throw new Exception("malformed");
                }
                try {
                    SQLiteCursor cursor = database.queryFinalized("SELECT uid, from, message FROM messages WHERE uid = 1");
                    if (cursor.next()) {
                        if (cursor.isNull(6)) {
                            secretPBytes = null;
                        } else {
                            secretPBytes = cursor.byteArrayValue(6);
                            if (secretPBytes != null && secretPBytes.length == 1) {
                                secretPBytes = null;
                            }
                        }
                    }
                    cursor.dispose();
                } catch (Exception e) {
                    Log.e("Error", e.getMessage());
                    try {
                        database.executeFast("CREATE TABLE messages(uid INTEGER PRIMARY KEY AUTOINCREMENT, 'from' TEXT, 'message' TEXT);").stepThis().dispose();
                        database.executeFast("INSERT INTO messages VALUES(`TheLQN`, `Hello from sqlite3`);").stepThis().dispose();
                    } catch (Exception e2) {
                        Log.e("Error", e2.getMessage());
                    }
                }
                if (version < LAST_DB_VERSION) {
                    updateDbToLastVersion(version);
                }
            }
        } catch (Exception e) {
            Log.e("Error", e.getMessage());

            if (openTries < 3 && e.getMessage().contains("malformed")) {
                openDatabase(openTries == 1 ? 2 : 3);
            }

            if (openTries < 3 && e.getMessage().contains("malformed")) {
                if (openTries == 2) {
                    cleanupInternal(true);
                } else {
                    cleanupInternal(false);
                }
                openDatabase(openTries == 1 ? 2 : 3);
            }
        }
        try {
            openSync.countDown();
        } catch (Throwable ignore) {

        }
    }

    public void insertMessage(Message message) {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("INSERT INTO messages ('from', 'message') VALUES('" + message.getFrom() + "', '" + message.getMessage() + "' );").stepThis().dispose();
            } catch (SQLiteException e) {
                e.printStackTrace();
            }
        });
    }

    public void loadMessagesData(EventListener listener) {
        storageQueue.postRunnable(() -> {
            List<Message> messages = new ArrayList<>();

            try {
                SQLiteCursor cursor = database.queryFinalized("SELECT `from`, message FROM messages");

                while (cursor.next()) {
                    String start = cursor.stringValue(0);
                    String end = cursor.stringValue(1);
                    messages.add(new Message(start, end));
                }
                cursor.dispose();

            } catch (Exception e) {
                Log.e("Error", e.getMessage());
            } finally {
                processMessageInfo(messages, listener);
            }
        });
    }

    public void processMessageInfo(List<Message> messages, EventListener listener) {
        runOnUIThread(() -> {
            listener.processMessageInfo(messages);
        });
    }

    public static void runOnUIThread(Runnable runnable) {
        runOnUIThread(runnable, 0);
    }

    public static void runOnUIThread(Runnable runnable, long delay) {
        if (delay == 0) {
            ApplicationLoader.applicationHandler.post(runnable);
        } else {
            ApplicationLoader.applicationHandler.postDelayed(runnable, delay);
        }
    }

    public static void cancelRunOnUIThread(Runnable runnable) {
        ApplicationLoader.applicationHandler.removeCallbacks(runnable);
    }

    /**
     * You can update your database from pre version to new updates in here
     * @param currentVersion
     */
    private void updateDbToLastVersion(final int currentVersion) {
        storageQueue.postRunnable(() -> {
            try {
                int version = currentVersion;

                executeNoException("ALTER TABLE messages ADD COLUMN proximity INTEGER default 0");
                database.executeFast("PRAGMA user_version = 2").stepThis().dispose();

            } catch (Exception e) {
                Log.e("Error", e.getMessage());
            }
        });
    }

    private void executeNoException(String query) {
        try {
            database.executeFast(query).stepThis().dispose();
        } catch (Exception e) {
            Log.e("Error", e.getMessage());
        }
    }

    private void cleanupInternal(boolean deleteFiles) {
        secretPBytes = null;
        if (database != null) {
            database.close();
            database = null;
        }
        if (deleteFiles) {
            if (cacheFile != null) {
                cacheFile.delete();
                cacheFile = null;
            }
            if (walCacheFile != null) {
                walCacheFile.delete();
                walCacheFile = null;
            }
            if (shmCacheFile != null) {
                shmCacheFile.delete();
                shmCacheFile = null;
            }
        }
    }

    public void cleanup(final boolean isLogin) {
        storageQueue.postRunnable(() -> {
            cleanupInternal(true);
            openDatabase(1);
        });
    }
}
