package com.thelqn.sample;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.thelqn.sample.model.EventListener;
import com.thelqn.sample.model.Message;
import com.thelqn.sqlite3.BuildVars;
import com.thelqn.sqlite3.DispatchQueue;
import com.thelqn.sqlite3.NativeByteBuffer;
import com.thelqn.sqlite3.SQLiteCursor;
import com.thelqn.sqlite3.SQLiteDatabase;
import com.thelqn.sqlite3.SQLiteException;
import com.thelqn.sqlite3.SQLitePreparedStatement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

public class MessagesStorage {

    private DispatchQueue storageQueue = new DispatchQueue("storageQueue");

    private SQLiteDatabase database;
    private File cacheFile;
    private File walCacheFile;
    private File shmCacheFile;

    private byte[] secretPBytes = null;

    private CountDownLatch openSync = new CountDownLatch(1);

    private final static int LAST_DB_VERSION = 1;

    /** Instance of the database storage */
    private static volatile MessagesStorage Instance = null;

    /**
     * Make a new Instance of the database
     * @return Instance of the database
     */
    public static MessagesStorage getInstance() {
        MessagesStorage localInstance = Instance;
        if (localInstance == null) {
            synchronized (MessagesStorage.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MessagesStorage();
                }
            }
        }
        return localInstance;
    }

    /** Default constructor */
    private MessagesStorage() {
        storageQueue.postRunnable(() -> openDatabase(1));
    }

    /**
     * Get
     * @return
     */
    public SQLiteDatabase getDatabase() {
        return database;
    }

    public DispatchQueue getStorageQueue() {
        return storageQueue;
    }

    /**
     * Returns true if the database is currently open.
     * @return True if the database is currently open (has not been closed).
     */
    public boolean isOpen() {
        return false;
    }

    public void openDatabase(int openTries) {
        File filesDir = ApplicationLoader.getFilesDirFixed();
        filesDir = new File(filesDir, "db/");
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

            database = new SQLiteDatabase(cacheFile.getPath(), ApplicationLoader.getFilesDirFixed().getPath());
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
            database.executeFast("PRAGMA temp_store = MEMORY").stepThis().dispose();
            database.executeFast("PRAGMA journal_mode = WAL").stepThis().dispose();
            database.executeFast("PRAGMA journal_size_limit = 10485760").stepThis().dispose();

            if (createTable) {
                if (BuildVars.LOGS_ENABLED) {
                    Log.d("Debug", "create new database");
                }

                onCreate(database, LAST_DB_VERSION);
                // Sets the database version.
                setVersion(LAST_DB_VERSION);

            } else {

                // Gets the database version.
                int version = getVersion();
                if (BuildVars.LOGS_ENABLED) {
                    Log.d("Debug", "current db version = " + version);
                }
                if (version == 0) {
                    throw new Exception("malformed");
                }
                if (version < LAST_DB_VERSION) {
                    onUpdate(database, LAST_DB_VERSION, version);
                    updateDbToLastVersion(version);
                }
            }
            onOpen(database);
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

    /**
     * Gets the database version.
     * @return the database version
     * @throws SQLiteException errors may accord during to run query
     */
    public int getVersion() throws SQLiteException {
        return database.executeInt("PRAGMA user_version");
    }

    /**
     * Sets the database version.
     * @param version the new database version
     * @throws SQLiteException errors may accord during to run query
     */
    public void setVersion(int version) throws SQLiteException {
        database.executeFast("PRAGMA user_version = " + LAST_DB_VERSION).stepThis().dispose();
    }

    /**
     * Gets the path to the database file.
     * @return The path to the database file.
     */
    public String getPath() {
        return cacheFile.getPath();
    }

    /**
     * Returns the current database size, in bytes.
     * @return the database size, in bytes
     */
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

    /**
     * You can update your database from pre version to new updates in here
     * @param currentVersion new version of the database
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

    public void cleanup() {
        storageQueue.postRunnable(() -> {
            cleanupInternal(true);
            openDatabase(1);
        });
    }

    protected void onCreate(@NonNull SQLiteDatabase database, int newVersion) throws SQLiteException {
        database.executeFast("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, 'from' TEXT, 'message' TEXT);").stepThis().dispose();
        database.executeFast("CREATE TABLE wallpapers(id INTEGER PRIMARY KEY AUTOINCREMENT, data BLOB)").stepThis().dispose();
    }

    protected void onOpen(@NonNull SQLiteDatabase database) {
    }

    protected void onUpdate(@NonNull SQLiteDatabase database, int newVersion, int oldVersion) {
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
                Log.e("Timer", "Data is loaded... " + System.currentTimeMillis());
                processMessageInfo(messages, listener);
            }
        });
    }

    public void putWallpapers(Bitmap wallPaper) {
        storageQueue.postRunnable(() -> {
            try {
                database.beginTransaction();
                SQLitePreparedStatement state;
                state = database.executeFast("INSERT INTO wallpapers(data) VALUES(?)");
                state.requery();

                //calculate how many bytes our image consists of.
                int bytes = wallPaper.getByteCount();
                //or we can calculate bytes this way. Use a different value than 4 if you don't use 32bit images.
                // int bytes = wallPaper.getWidth()*wallPaper.getHeight()*4;

                ByteBuffer buffer = ByteBuffer.allocate(bytes); //Create a new buffer
                wallPaper.copyPixelsToBuffer(buffer); //Move the byte data to the buffer
                byte[] array = buffer.array();

                NativeByteBuffer data = new NativeByteBuffer(bytes);
                data.writeBytes(array);
                state.bindByteBuffer(1, data);
                state.step();

                data.reuse();

                state.dispose();
            } catch (Exception e) {
                Log.e("Error put wallpaper", e.getMessage());
                e.printStackTrace();
            }finally {
                database.commitTransaction();
            }
        });
    }

    public void deleteWallpaper(long id) {
        storageQueue.postRunnable(() -> {
            try {
                database.executeFast("DELETE FROM wallpapers WHERE id = " + id).stepThis().dispose();
            } catch (Exception e) {
                Log.e("Error delete wallpaper", e.getMessage());
            }
        });
    }

}

//    public void saveSecretParams(final int lsv, final int sg, final byte[] pbytes) {
//        storageQueue.postRunnable(() -> {
//            try {
//                SQLitePreparedStatement state = database.executeFast("UPDATE params SET lsv = ?, sg = ?, pbytes = ? WHERE id = 1");
//                state.bindInteger(1, lsv);
//                state.bindInteger(2, sg);
//                NativeByteBuffer data = new NativeByteBuffer(pbytes != null ? pbytes.length : 1);
//                if (pbytes != null) {
//                    data.writeBytes(pbytes);
//                }
//                state.bindByteBuffer(3, data);
//                state.step();
//                state.dispose();
//                data.reuse();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public long createPendingTask(final NativeByteBuffer data) {
//        if (data == null) {
//            return 0;
//        }
//        final long id = lastTaskId.getAndAdd(1);
//        storageQueue.postRunnable(() -> {
//            try {
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO pending_tasks VALUES(?, ?)");
//                state.bindLong(1, id);
//                state.bindByteBuffer(2, data);
//                state.step();
//                state.dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            } finally {
//                data.reuse();
//            }
//        });
//        return id;
//    }
//
//    public long createPendingTask(final NativeByteBuffer data) {
//        if (data == null) {
//            return 0;
//        }
//        final long id = lastTaskId.getAndAdd(1);
//        storageQueue.postRunnable(() -> {
//            try {
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO pending_tasks VALUES(?, ?)");
//                state.bindLong(1, id);
//                state.bindByteBuffer(2, data);
//                state.step();
//                state.dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            } finally {
//                data.reuse();
//            }
//        });
//        return id;
//    }
//
//    public void putPushMessage(MessageObject message) {
//        storageQueue.postRunnable(() -> {
//            try {
//                NativeByteBuffer data = new NativeByteBuffer(message.messageOwner.getObjectSize());
//                message.messageOwner.serializeToStream(data);
//
//                long messageId = message.getId();
//                if (message.messageOwner.peer_id.channel_id != 0) {
//                    messageId |= ((long) message.messageOwner.peer_id.channel_id) << 32;
//                }
//
//                int flags = 0;
//                if (message.localType == 2) {
//                    flags |= 1;
//                }
//                if (message.localChannel) {
//                    flags |= 2;
//                }
//
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO unread_push_messages VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)");
//                state.requery();
//                state.bindLong(1, message.getDialogId());
//                state.bindLong(2, messageId);
//                state.bindLong(3, message.messageOwner.random_id);
//                state.bindInteger(4, message.messageOwner.date);
//                state.bindByteBuffer(5, data);
//                if (message.messageText == null) {
//                    state.bindNull(6);
//                } else {
//                    state.bindString(6, message.messageText.toString());
//                }
//                if (message.localName == null) {
//                    state.bindNull(7);
//                } else {
//                    state.bindString(7, message.localName);
//                }
//                if (message.localUserName == null) {
//                    state.bindNull(8);
//                } else {
//                    state.bindString(8, message.localUserName);
//                }
//                state.bindInteger(9, flags);
//                state.step();
//
//                data.reuse();
//                state.dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void readAllDialogs(int folderId) {
//        storageQueue.postRunnable(() -> {
//            try {
//                ArrayList<Integer> usersToLoad = new ArrayList<>();
//                ArrayList<Integer> chatsToLoad = new ArrayList<>();
//                ArrayList<Integer> encryptedChatIds = new ArrayList<>();
//
//                final LongSparseArray<ReadDialog> dialogs = new LongSparseArray<>();
//                SQLiteCursor cursor;
//                if (folderId >= 0) {
//                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT did, last_mid, unread_count, date FROM dialogs WHERE unread_count > 0 AND folder_id = %1$d", folderId));
//                } else {
//                    cursor = database.queryFinalized("SELECT did, last_mid, unread_count, date FROM dialogs WHERE unread_count > 0");
//                }
//                while (cursor.next()) {
//                    long did = cursor.longValue(0);
//                    if (DialogObject.isFolderDialogId(did)) {
//                        continue;
//                    }
//                    ReadDialog dialog = new ReadDialog();
//                    dialog.lastMid = cursor.intValue(1);
//                    dialog.unreadCount = cursor.intValue(2);
//                    dialog.date = cursor.intValue(3);
//
//                    dialogs.put(did, dialog);
//                    int lower_id = (int) did;
//                    int high_id = (int) (did >> 32);
//                    if (lower_id != 0) {
//                        if (lower_id < 0) {
//                            if (!chatsToLoad.contains(-lower_id)) {
//                                chatsToLoad.add(-lower_id);
//                            }
//                        } else {
//                            if (!usersToLoad.contains(lower_id)) {
//                                usersToLoad.add(lower_id);
//                            }
//                        }
//                    } else {
//                        if (!encryptedChatIds.contains(high_id)) {
//                            encryptedChatIds.add(high_id);
//                        }
//                    }
//                }
//                cursor.dispose();
//
//                final ArrayList<TLRPC.User> users = new ArrayList<>();
//                final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
//                final ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
//                if (!encryptedChatIds.isEmpty()) {
//                    getEncryptedChatsInternal(TextUtils.join(",", encryptedChatIds), encryptedChats, usersToLoad);
//                }
//                if (!usersToLoad.isEmpty()) {
//                    getUsersInternal(TextUtils.join(",", usersToLoad), users);
//                }
//                if (!chatsToLoad.isEmpty()) {
//                    getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
//                }
//                AndroidUtilities.runOnUIThread(() -> {
//                    getMessagesController().putUsers(users, true);
//                    getMessagesController().putChats(chats, true);
//                    getMessagesController().putEncryptedChats(encryptedChats, true);
//                    for (int a = 0; a < dialogs.size(); a++) {
//                        long did = dialogs.keyAt(a);
//                        ReadDialog dialog = dialogs.valueAt(a);
//                        getMessagesController().markDialogAsRead(did, dialog.lastMid, dialog.lastMid, dialog.date, false, 0, dialog.unreadCount, true, 0);
//                    }
//                });
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//
//    private TLRPC.messages_Dialogs loadDialogsByIds(String ids, ArrayList<Integer> usersToLoad, ArrayList<Integer> chatsToLoad, ArrayList<Integer> encryptedToLoad) throws Exception {
//        TLRPC.messages_Dialogs dialogs = new TLRPC.TL_messages_dialogs();
//        ArrayList<Long> replyMessages = new ArrayList<>();
//        LongSparseArray<TLRPC.Message> replyMessageOwners = new LongSparseArray<>();
//
//        SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT d.did, d.last_mid, d.unread_count, d.date, m.data, m.read_state, m.mid, m.send_state, s.flags, m.date, d.pts, d.inbox_max, d.outbox_max, m.replydata, d.pinned, d.unread_count_i, d.flags, d.folder_id, d.data FROM dialogs as d LEFT JOIN messages as m ON d.last_mid = m.mid LEFT JOIN dialog_settings as s ON d.did = s.did WHERE d.did IN (%s) ORDER BY d.pinned DESC, d.date DESC", ids));
//        while (cursor.next()) {
//            long dialogId = cursor.longValue(0);
//            TLRPC.Dialog dialog = new TLRPC.TL_dialog();
//            dialog.id = dialogId;
//            dialog.top_message = cursor.intValue(1);
//            dialog.unread_count = cursor.intValue(2);
//            dialog.last_message_date = cursor.intValue(3);
//            dialog.pts = cursor.intValue(10);
//            dialog.flags = dialog.pts == 0 || (int) dialog.id > 0 ? 0 : 1;
//            dialog.read_inbox_max_id = cursor.intValue(11);
//            dialog.read_outbox_max_id = cursor.intValue(12);
//            dialog.pinnedNum = cursor.intValue(14);
//            dialog.pinned = dialog.pinnedNum != 0;
//            dialog.unread_mentions_count = cursor.intValue(15);
//            int dialog_flags = cursor.intValue(16);
//            dialog.unread_mark = (dialog_flags & 1) != 0;
//            long flags = cursor.longValue(8);
//            int low_flags = (int) flags;
//            dialog.notify_settings = new TLRPC.TL_peerNotifySettings();
//            if ((low_flags & 1) != 0) {
//                dialog.notify_settings.mute_until = (int) (flags >> 32);
//                if (dialog.notify_settings.mute_until == 0) {
//                    dialog.notify_settings.mute_until = Integer.MAX_VALUE;
//                }
//            }
//            dialog.folder_id = cursor.intValue(17);
//            dialogs.dialogs.add(dialog);
//
//            NativeByteBuffer data = cursor.byteBufferValue(4);
//            if (data != null) {
//                TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
//                if (message != null) {
//                    message.readAttachPath(data, getUserConfig().clientUserId);
//                    data.reuse();
//                    MessageObject.setUnreadFlags(message, cursor.intValue(5));
//                    message.id = cursor.intValue(6);
//                    int date = cursor.intValue(9);
//                    if (date != 0) {
//                        dialog.last_message_date = date;
//                    }
//                    message.send_state = cursor.intValue(7);
//                    message.dialog_id = dialog.id;
//                    dialogs.messages.add(message);
//
//                    addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad);
//
//                    try {
//                        if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0 && (
//                                message.action instanceof TLRPC.TL_messageActionPinMessage ||
//                                        message.action instanceof TLRPC.TL_messageActionPaymentSent ||
//                                        message.action instanceof TLRPC.TL_messageActionGameScore)) {
//                            if (!cursor.isNull(13)) {
//                                NativeByteBuffer data2 = cursor.byteBufferValue(13);
//                                if (data2 != null) {
//                                    message.replyMessage = TLRPC.Message.TLdeserialize(data2, data2.readInt32(false), false);
//                                    message.replyMessage.readAttachPath(data2, getUserConfig().clientUserId);
//                                    data2.reuse();
//                                    if (message.replyMessage != null) {
//                                        addUsersAndChatsFromMessage(message.replyMessage, usersToLoad, chatsToLoad);
//                                    }
//                                }
//                            }
//                            if (message.replyMessage == null) {
//                                long messageId = message.reply_to.reply_to_msg_id;
//                                if (message.reply_to.reply_to_peer_id != null) {
//                                    if (message.reply_to.reply_to_peer_id.channel_id != 0) {
//                                        messageId |= ((long) message.reply_to.reply_to_peer_id.channel_id) << 32;
//                                    }
//                                } else if (message.peer_id.channel_id != 0) {
//                                    messageId |= ((long) message.peer_id.channel_id) << 32;
//                                }
//                                if (!replyMessages.contains(messageId)) {
//                                    replyMessages.add(messageId);
//                                }
//                                replyMessageOwners.put(dialog.id, message);
//                            }
//                        }
//                    } catch (Exception e) {
//                        FileLog.e(e);
//                    }
//                } else {
//                    data.reuse();
//                }
//            }
//
//            int lower_id = (int) dialog.id;
//            int high_id = (int) (dialog.id >> 32);
//            if (lower_id != 0) {
//                if (lower_id > 0) {
//                    if (!usersToLoad.contains(lower_id)) {
//                        usersToLoad.add(lower_id);
//                    }
//                } else {
//                    if (!chatsToLoad.contains(-lower_id)) {
//                        chatsToLoad.add(-lower_id);
//                    }
//                }
//            } else {
//                if (!encryptedToLoad.contains(high_id)) {
//                    encryptedToLoad.add(high_id);
//                }
//            }
//        }
//        cursor.dispose();
//
//        if (!replyMessages.isEmpty()) {
//            SQLiteCursor replyCursor = database.queryFinalized(String.format(Locale.US, "SELECT data, mid, date, uid FROM messages WHERE mid IN(%s)", TextUtils.join(",", replyMessages)));
//            while (replyCursor.next()) {
//                NativeByteBuffer data = replyCursor.byteBufferValue(0);
//                if (data != null) {
//                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
//                    message.readAttachPath(data, getUserConfig().clientUserId);
//                    data.reuse();
//                    message.id = replyCursor.intValue(1);
//                    message.date = replyCursor.intValue(2);
//                    message.dialog_id = replyCursor.longValue(3);
//
//                    addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad);
//
//                    TLRPC.Message owner = replyMessageOwners.get(message.dialog_id);
//                    if (owner != null) {
//                        owner.replyMessage = message;
//                        message.dialog_id = owner.dialog_id;
//                    }
//                }
//            }
//            replyCursor.dispose();
//        }
//        return dialogs;
//    }
//
//    public void removePendingTask(final long id) {
//        storageQueue.postRunnable(() -> {
//            try {
//                database.executeFast("DELETE FROM pending_tasks WHERE id = " + id).stepThis().dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void putDialogPhotos(int did, TLRPC.photos_Photos photos, ArrayList<TLRPC.Message> messages) {
//        if (photos == null) {
//            return;
//        }
//        storageQueue.postRunnable(() -> {
//            try {
//                database.executeFast("DELETE FROM user_photos WHERE uid = " + did).stepThis().dispose();
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO user_photos VALUES(?, ?, ?)");
//                for (int a = 0, N = photos.photos.size(); a < N; a++) {
//                    TLRPC.Photo photo = photos.photos.get(a);
//                    if (photo instanceof TLRPC.TL_photoEmpty) {
//                        continue;
//                    }
//                    state.requery();
//                    int size = photo.getObjectSize();
//                    if (messages != null) {
//                        size += messages.get(a).getObjectSize();
//                    }
//                    NativeByteBuffer data = new NativeByteBuffer(size);
//                    photo.serializeToStream(data);
//                    if (messages != null) {
//                        messages.get(a).serializeToStream(data);
//                    }
//                    state.bindInteger(1, did);
//                    state.bindLong(2, photo.id);
//                    state.bindByteBuffer(3, data);
//                    state.step();
//                    data.reuse();
//                }
//                state.dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void updateMessageReactions(long dialogId, int msgId, int channelId, TLRPC.TL_messageReactions reactions) {
//        storageQueue.postRunnable(() -> {
//            try {
//                database.beginTransaction();
//                long mid = msgId;
//                if (channelId != 0) {
//                    mid |= ((long) channelId) << 32;
//                }
//                SQLiteCursor cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM messages WHERE mid = %d", mid));
//                if (cursor.next()) {
//                    NativeByteBuffer data = cursor.byteBufferValue(0);
//                    if (data != null) {
//                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
//                        if (message != null) {
//                            message.readAttachPath(data, getUserConfig().clientUserId);
//                            data.reuse();
//                            MessageObject.updateReactions(message, reactions);
//                            SQLitePreparedStatement state = database.executeFast("UPDATE messages SET data = ? WHERE mid = ?");
//                            NativeByteBuffer data2 = new NativeByteBuffer(message.getObjectSize());
//                            message.serializeToStream(data2);
//                            state.requery();
//                            state.bindByteBuffer(1, data2);
//                            state.bindLong(2, mid);
//                            state.step();
//                            data2.reuse();
//                            state.dispose();
//                        } else {
//                            data.reuse();
//                        }
//                    }
//                }
//                cursor.dispose();
//                database.commitTransaction();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void markMentionMessageAsRead(final int messageId, final int channelId, final long did) {
//        storageQueue.postRunnable(() -> {
//            try {
//                long mid = messageId;
//                if (channelId != 0) {
//                    mid |= ((long) channelId) << 32;
//                }
//
//                database.executeFast(String.format(Locale.US, "UPDATE messages SET read_state = read_state | 2 WHERE mid = %d", mid)).stepThis().dispose();
//
//                SQLiteCursor cursor = database.queryFinalized("SELECT unread_count_i FROM dialogs WHERE did = " + did);
//                int old_mentions_count = 0;
//                if (cursor.next()) {
//                    old_mentions_count = Math.max(0, cursor.intValue(0) - 1);
//                }
//                cursor.dispose();
//                database.executeFast(String.format(Locale.US, "UPDATE dialogs SET unread_count_i = %d WHERE did = %d", old_mentions_count, did)).stepThis().dispose();
//                LongSparseArray<Integer> sparseArray = new LongSparseArray<>(1);
//                sparseArray.put(did, old_mentions_count);
//                if (old_mentions_count == 0) {
//                    updateFiltersReadCounter(null, sparseArray, true);
//                }
//                getMessagesController().processDialogsUpdateRead(null, sparseArray);
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void loadChannelAdmins(final int chatId) {
//        storageQueue.postRunnable(() -> {
//            try {
//                SQLiteCursor cursor = database.queryFinalized("SELECT uid, data FROM channel_admins_v3 WHERE did = " + chatId);
//                SparseArray<TLRPC.ChannelParticipant> ids = new SparseArray<>();
//                while (cursor.next()) {
//                    NativeByteBuffer data = cursor.byteBufferValue(1);
//                    if (data != null) {
//                        TLRPC.ChannelParticipant participant = TLRPC.ChannelParticipant.TLdeserialize(data, data.readInt32(false), false);
//                        data.reuse();
//                        if (participant != null) {
//                            ids.put(cursor.intValue(0), participant);
//                        }
//                    }
//                }
//                cursor.dispose();
//                getMessagesController().processLoadedChannelAdmins(ids, chatId, true);
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void putChannelAdmins(final int chatId, final SparseArray<TLRPC.ChannelParticipant> ids) {
//        storageQueue.postRunnable(() -> {
//            try {
//                database.executeFast("DELETE FROM channel_admins_v3 WHERE did = " + chatId).stepThis().dispose();
//                database.beginTransaction();
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO channel_admins_v3 VALUES(?, ?, ?)");
//                int date = (int) (System.currentTimeMillis() / 1000);
//                NativeByteBuffer data;
//                for (int a = 0; a < ids.size(); a++) {
//                    state.requery();
//                    state.bindInteger(1, chatId);
//                    state.bindInteger(2, ids.keyAt(a));
//                    TLRPC.ChannelParticipant participant = ids.valueAt(a);
//                    data = new NativeByteBuffer(participant.getObjectSize());
//                    participant.serializeToStream(data);
//                    state.bindByteBuffer(3, data);
//                    state.step();
//                    data.reuse();
//                }
//                state.dispose();
//                database.commitTransaction();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void updateChannelUsers(final int channelId, final ArrayList<TLRPC.ChannelParticipant> participants) {
//        storageQueue.postRunnable(() -> {
//            try {
//                long did = -channelId;
//                database.executeFast("DELETE FROM channel_users_v2 WHERE did = " + did).stepThis().dispose();
//                database.beginTransaction();
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO channel_users_v2 VALUES(?, ?, ?, ?)");
//                NativeByteBuffer data;
//                int date = (int) (System.currentTimeMillis() / 1000);
//                for (int a = 0; a < participants.size(); a++) {
//                    TLRPC.ChannelParticipant participant = participants.get(a);
//                    state.requery();
//                    state.bindLong(1, did);
//                    state.bindInteger(2, MessageObject.getPeerId(participant.peer));
//                    state.bindInteger(3, date);
//                    data = new NativeByteBuffer(participant.getObjectSize());
//                    participant.serializeToStream(data);
//                    state.bindByteBuffer(4, data);
//                    state.step();
//                    data.reuse();
//                    date--;
//                }
//                state.dispose();
//                database.commitTransaction();
//                loadChatInfo(channelId, true, null, false, true);
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void putContacts(ArrayList<TLRPC.TL_contact> contacts, final boolean deleteAll) {
//        if (contacts.isEmpty() && !deleteAll) {
//            return;
//        }
//        final ArrayList<TLRPC.TL_contact> contactsCopy = new ArrayList<>(contacts);
//        storageQueue.postRunnable(() -> {
//            try {
//                if (deleteAll) {
//                    database.executeFast("DELETE FROM contacts WHERE 1").stepThis().dispose();
//                }
//                database.beginTransaction();
//                SQLitePreparedStatement state = database.executeFast("REPLACE INTO contacts VALUES(?, ?)");
//                for (int a = 0; a < contactsCopy.size(); a++) {
//                    TLRPC.TL_contact contact = contactsCopy.get(a);
//                    state.requery();
//                    state.bindInteger(1, contact.user_id);
//                    state.bindInteger(2, contact.mutual ? 1 : 0);
//                    state.step();
//                }
//                state.dispose();
//                database.commitTransaction();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void deleteContacts(final ArrayList<Integer> uids) {
//        if (uids == null || uids.isEmpty()) {
//            return;
//        }
//        storageQueue.postRunnable(() -> {
//            try {
//                String ids = TextUtils.join(",", uids);
//                database.executeFast("DELETE FROM contacts WHERE uid IN(" + ids + ")").stepThis().dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//
//    public void putSentFile(final String path, final TLObject file, final int type, String parent) {
//        if (path == null || file == null || parent == null) {
//            return;
//        }
//        storageQueue.postRunnable(() -> {
//            SQLitePreparedStatement state = null;
//            try {
//                String id = Utilities.MD5(path);
//                if (id != null) {
//                    TLRPC.MessageMedia messageMedia = null;
//                    if (file instanceof TLRPC.Photo) {
//                        messageMedia = new TLRPC.TL_messageMediaPhoto();
//                        messageMedia.photo = (TLRPC.Photo) file;
//                        messageMedia.flags |= 1;
//                    } else if (file instanceof TLRPC.Document) {
//                        messageMedia = new TLRPC.TL_messageMediaDocument();
//                        messageMedia.document = (TLRPC.Document) file;
//                        messageMedia.flags |= 1;
//                    }
//                    if (messageMedia == null) {
//                        return;
//                    }
//                    state = database.executeFast("REPLACE INTO sent_files_v2 VALUES(?, ?, ?, ?)");
//                    state.requery();
//                    NativeByteBuffer data = new NativeByteBuffer(messageMedia.getObjectSize());
//                    messageMedia.serializeToStream(data);
//                    state.bindString(1, id);
//                    state.bindInteger(2, type);
//                    state.bindByteBuffer(3, data);
//                    state.bindString(4, parent);
//                    state.step();
//                    data.reuse();
//                }
//            } catch (Exception e) {
//                FileLog.e(e);
//            } finally {
//                if (state != null) {
//                    state.dispose();
//                }
//            }
//        });
//    }
//
//    public void getDialogPhotos(final int did, final int count, final long max_id, final int classGuid) {
//        storageQueue.postRunnable(() -> {
//            try {
//                SQLiteCursor cursor;
//
//                if (max_id != 0) {
//                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d AND id < %d ORDER BY rowid ASC LIMIT %d", did, max_id, count));
//                } else {
//                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM user_photos WHERE uid = %d ORDER BY rowid ASC LIMIT %d", did, count));
//                }
//
//                final TLRPC.photos_Photos res = new TLRPC.TL_photos_photos();
//                ArrayList<TLRPC.Message> messages = new ArrayList<>();
//
//                while (cursor.next()) {
//                    NativeByteBuffer data = cursor.byteBufferValue(0);
//                    if (data != null) {
//                        TLRPC.Photo photo = TLRPC.Photo.TLdeserialize(data, data.readInt32(false), false);
//                        if (data.remaining() > 0) {
//                            messages.add(TLRPC.Message.TLdeserialize(data, data.readInt32(false), false));
//                        } else {
//                            messages.add(null);
//                        }
//                        data.reuse();
//                        res.photos.add(photo);
//                    }
//                }
//                cursor.dispose();
//
//                Utilities.stageQueue.postRunnable(() -> getMessagesController().processLoadedUserPhotos(res, messages, did, count, max_id, true, classGuid));
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void clearUserPhotos(final int uid) {
//        storageQueue.postRunnable(() -> {
//            try {
//                database.executeFast("DELETE FROM user_photos WHERE uid = " + uid).stepThis().dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//
//    public void clearUserPhoto(final int uid, final long pid) {
//        storageQueue.postRunnable(() -> {
//            try {
//                database.executeFast("DELETE FROM user_photos WHERE uid = " + uid + " AND id = " + pid).stepThis().dispose();
//            } catch (Exception e) {
//                FileLog.e(e);
//            }
//        });
//    }
//