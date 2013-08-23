
package de.triplet.simpleprovider;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("unused")
public abstract class AbstractProvider extends ContentProvider {

    protected final String mTag;
    protected SQLiteDatabase mDatabase;

    protected AbstractProvider() {
        mTag = getClass().getName();
    }

    @Override
    public final boolean onCreate() {
        SQLiteOpenHelper dbHelper = new SQLHelper(getContext());
        try {
            mDatabase = dbHelper.getWritableDatabase();
        } catch (SQLiteException e) {
            mDatabase = null;
            Log.w(mTag, "Database Opening exception", e);
        }

        return mDatabase != null;
    }

    protected abstract String getAuthority();

    protected String getDatabaseFileName() {
        return getClass().getName().toLowerCase() + ".db";
    }

    /**
     * Return the schema version of the database (starting at 1).<br>
     * <br>
     * This number is used to announce changes to the database schema. If the
     * database is older, {@link #onUpgrade(SQLiteDatabase, int, int)} will be
     * used to upgrade the database. If the database is newer,
     * {@link #onDowngrade(SQLiteDatabase, int, int)} will be used to downgrade
     * the database.
     */
    protected int getSchemaVersion() {
        /* Override in derived classes */
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        final SelectionBuilder builder = buildBaseQuery(uri);
        final Cursor cursor = builder.where(selection, selectionArgs).query(mDatabase, projection,
                sortOrder);
        if (cursor != null) {
            cursor.setNotificationUri(getContentResolver(), uri);
        }
        return cursor;
    }

    private ContentResolver getContentResolver() {
        Context context = getContext();
        if (context == null) {
            return null;
        }

        return context.getContentResolver();
    }

    private SelectionBuilder buildBaseQuery(Uri uri) {
        List<String> pathSegments = uri.getPathSegments();
        if (pathSegments == null) {
            return null;
        }

        SelectionBuilder builder = new SelectionBuilder(pathSegments.get(0));

        if (pathSegments.size() == 2) {
            builder.whereEquals(BaseColumns._ID, uri.getLastPathSegment());
        }

        return builder;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        List<String> segments = uri.getPathSegments();
        if (segments == null || segments.size() != 1) {
            return null;
        }

        long rowId = mDatabase.insert(segments.get(0), null, values);

        if (rowId > -1) {
            getContentResolver().notifyChange(uri, null);

            return ContentUris.withAppendedId(uri, rowId);
        }

        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        final SelectionBuilder builder = buildBaseQuery(uri);
        int count = builder.where(selection, selectionArgs).delete(mDatabase);

        if (count > 0) {
            getContentResolver().notifyChange(uri, null);
        }

        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        final SelectionBuilder builder = buildBaseQuery(uri);
        int count = builder.where(selection, selectionArgs).update(mDatabase, values);

        if (count > 0) {
            getContentResolver().notifyChange(uri, null);
        }

        return count;

    }

    @Override
    public final ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        ContentProviderResult[] result = null;
        mDatabase.beginTransaction();
        try {
            result = super.applyBatch(operations);
            mDatabase.setTransactionSuccessful();
        } finally {
            mDatabase.endTransaction();
        }
        return result;
    }

    protected void onCreate(SQLiteDatabase db) {
        createTables(db);
    }

    /**
     * Called when the database needs to be upgraded. The implementation should
     * use this method to drop tables, add tables, or do anything else it needs
     * to upgrade to the new schema version.
     * <p>
     * The SQLite ALTER TABLE documentation can be found <a
     * href="http://sqlite.org/lang_altertable.html">here</a>. If you add new
     * columns you can use ALTER TABLE to insert them into a live table. If you
     * rename or remove columns you can use ALTER TABLE to rename the old table,
     * then create the new table and then populate the new table with the
     * contents of the old table.
     * </p>
     * <p>
     * This method executes within a transaction. If an exception is thrown, all
     * changes will automatically be rolled back.
     * </p>
     *
     * @param db         The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    protected void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        /* Override in derived classes */
        upgradeTables(db, oldVersion, newVersion);
    }

    /**
     * Called when the database needs to be downgraded. This is strictly similar
     * to {@link #onUpgrade} method, but is called whenever current version is
     * newer than requested one. However, this method is not abstract, so it is
     * not mandatory for a customer to implement it. If not overridden, default
     * implementation will reject downgrade and throws SQLiteException
     * <p>
     * This method executes within a transaction. If an exception is thrown, all
     * changes will automatically be rolled back.
     * </p>
     *
     * @param db         The database.
     * @param oldVersion The old database version.
     * @param newVersion The new database version.
     */
    protected void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        /* Override in derived classes */
        throw new SQLiteException("Can't downgrade database from version " + oldVersion + " to "
                + newVersion);
    }

    private void createTables(SQLiteDatabase db) {
        for (Class<?> clazz : getClass().getClasses()) {
            Table table = clazz.getAnnotation(Table.class);
            if (table != null) {
                createTable(db, table.value(), clazz);
            }
        }
    }

    private void createTable(SQLiteDatabase db, String tableName, Class<?> tableClass) {
        ArrayList<String> columns = new ArrayList<String>();
        for (Field field : tableClass.getFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                try {
                    columns.add(field.get(null) + " " + column.value());
                } catch (Exception e) {
                    Log.e(mTag, "Error accessing " + field, e);
                }
            }
        }

        db.execSQL("CREATE TABLE " + tableName + " (" + TextUtils.join(", ", columns) + ");");
    }

    private void upgradeTables(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(mTag, "Upgrading Tables: " + oldVersion + " -> " + newVersion);

        for (Class<?> clazz : getClass().getClasses()) {
            Table table = clazz.getAnnotation(Table.class);
            if (table != null) {
                upgradeTable(db, oldVersion, newVersion, table.value(), clazz);
            }
        }
    }

    private void upgradeTable(SQLiteDatabase db, int oldVersion, int newVersion, String tableName, Class<?> tableClass) {
        for (Field field : tableClass.getFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                int since = column.since();
                if (oldVersion < since && newVersion >= since) {
                    try {
                        Object value = field.get(null);
                        Log.d(mTag, "Adding column " + value);
                        db.execSQL("ALTER TABLE " + tableName + " ADD COLUMN " + value + " " + column.value() + ";");
                    } catch (Exception e) {
                        Log.e(mTag, "Error accessing " + field, e);
                    }
                }
            }
        }
    }

    private class SQLHelper extends SQLiteOpenHelper {

        public SQLHelper(Context context) {
            super(context, getDatabaseFileName(), null, getSchemaVersion());
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            AbstractProvider.this.onCreate(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            AbstractProvider.this.onUpgrade(db, oldVersion, newVersion);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            AbstractProvider.this.onDowngrade(db, oldVersion, newVersion);
        }

    }

}
