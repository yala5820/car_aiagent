package langchain4j.chat_memory_sqlite;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

public class PersistentChatMemorySqlite implements ChatMemoryStore {
    private final ChatMemoryDbHelper dbHelper;

    public PersistentChatMemorySqlite(Context context, String memoryName) {
        String fileName = memoryName + ".db";
        dbHelper = new ChatMemoryDbHelper(context, fileName);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String[] projection = {ChatMemoryContract.MemoryEntry.COLUMN_NAME_MESSAGES};
        String selection = ChatMemoryContract.MemoryEntry.COLUMN_NAME_MEMORY_ID + " = ?";
        String[] selectionArgs = {memoryId.toString()};

        try (Cursor cursor = db.query(
                ChatMemoryContract.MemoryEntry.TABLE_NAME,
                projection,
                selection,
                selectionArgs,
                null, null, null)) {
            if (cursor.moveToFirst()) {
                String json = cursor.getString(cursor.getColumnIndexOrThrow(
                        ChatMemoryContract.MemoryEntry.COLUMN_NAME_MESSAGES));
                return messagesFromJson(json);
            }
        }
        return new ArrayList<>();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String json = messagesToJson(messages);
        ContentValues values = new ContentValues();
        values.put(ChatMemoryContract.MemoryEntry.COLUMN_NAME_MEMORY_ID, memoryId.toString());
        values.put(ChatMemoryContract.MemoryEntry.COLUMN_NAME_MESSAGES, json);

        db.insertWithOnConflict(
                ChatMemoryContract.MemoryEntry.TABLE_NAME,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String selection = ChatMemoryContract.MemoryEntry.COLUMN_NAME_MEMORY_ID + " = ?";
        String[] selectionArgs = {memoryId.toString()};
        db.delete(ChatMemoryContract.MemoryEntry.TABLE_NAME, selection, selectionArgs);
    }

    private static class ChatMemoryDbHelper extends SQLiteOpenHelper {
        private static final int DATABASE_VERSION = 1;

        public ChatMemoryDbHelper(Context context, String fileName) {
            super(context, fileName, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(ChatMemoryContract.SQL_CREATE_ENTRIES);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL(ChatMemoryContract.SQL_DELETE_ENTRIES);
            onCreate(db);
        }
    }

    private static class ChatMemoryContract {
        private static final String SQL_CREATE_ENTRIES =
                "CREATE TABLE " + MemoryEntry.TABLE_NAME + " (" +
                        MemoryEntry.COLUMN_NAME_MEMORY_ID + " TEXT PRIMARY KEY," +
                        MemoryEntry.COLUMN_NAME_MESSAGES + " TEXT)";

        private static final String SQL_DELETE_ENTRIES =
                "DROP TABLE IF EXISTS " + MemoryEntry.TABLE_NAME;

        static class MemoryEntry {
            static final String TABLE_NAME = "chat_memory";
            static final String COLUMN_NAME_MEMORY_ID = "memory_id";
            static final String COLUMN_NAME_MESSAGES = "messages";
        }
    }
}
