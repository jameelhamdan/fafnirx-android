package app.fafnirx.android.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Folder(val id: Long, val treeUri: String, val name: String, val prefix: String)

class Counts(val synced: Int, val waiting: Int, val failed: Int) {
    val total get() = synced + waiting + failed
}

/** A file waiting to be sent. */
class Pending(
    val folderId: Long,
    val docId: String,
    val relPath: String,
    val size: Long,
    val mtime: Long,
    val root: ByteArray?,
    val chunks: ByteArray?,
    val failCount: Int,
) {
    val key get() = "$folderId:$docId"
}

/** Which folders are watched and what happened to each file in them. Nothing here touches the files themselves. */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "fafnirx.db", null, 1) {
    init {
        // Readers (the screens) must not wait for the long write transaction of a folder scan.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folder (id INTEGER PRIMARY KEY AUTOINCREMENT, tree_uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL, prefix TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE file (folder_id INTEGER NOT NULL, doc_id TEXT NOT NULL, rel_path TEXT NOT NULL, size INTEGER NOT NULL, " +
                "mtime INTEGER NOT NULL, state INTEGER NOT NULL DEFAULT 0, root BLOB, chunks BLOB, fail_count INTEGER NOT NULL DEFAULT 0, " +
                "retry_at INTEGER NOT NULL DEFAULT 0, last_error TEXT, seen INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (folder_id, doc_id))",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun folders(): List<Folder> = readableDatabase.rawQuery("SELECT id, tree_uri, name, prefix FROM folder ORDER BY id", null).use {
        buildList { while (it.moveToNext()) add(Folder(it.getLong(0), it.getString(1), it.getString(2), it.getString(3))) }
    }

    fun addFolder(treeUri: String, name: String): Folder? {
        val db = writableDatabase
        if (folders().any { it.treeUri == treeUri }) return null
        val prefix = app.fafnirx.android.sync.Paths.uniquePrefix(name, folders().map { it.prefix }.toSet())
        val id = db.insert("folder", null, ContentValues().apply { put("tree_uri", treeUri); put("name", name); put("prefix", prefix) })
        return Folder(id, treeUri, name, prefix)
    }

    fun removeFolder(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("file", "folder_id = ?", arrayOf(id.toString()))
            db.delete("folder", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Records a file seen by a scan. A file that is new or whose size, time or place changed goes back to "new". */
    fun seen(folderId: Long, docId: String, relPath: String, size: Long, mtime: Long, generation: Long) {
        val db = writableDatabase
        val args = arrayOf(folderId.toString(), docId)
        val same = db.rawQuery("SELECT size, mtime, rel_path FROM file WHERE folder_id = ? AND doc_id = ?", args).use {
            if (!it.moveToFirst()) null else it.getLong(0) == size && it.getLong(1) == mtime && it.getString(2) == relPath
        }
        when (same) {
            true -> db.execSQL("UPDATE file SET seen = ? WHERE folder_id = ? AND doc_id = ?", arrayOf(generation, folderId, docId))
            false -> db.execSQL(
                "UPDATE file SET size = ?, mtime = ?, rel_path = ?, state = 0, root = NULL, chunks = NULL, fail_count = 0, retry_at = 0, " +
                    "last_error = NULL, seen = ? WHERE folder_id = ? AND doc_id = ?",
                arrayOf(size, mtime, relPath, generation, folderId, docId),
            )
            null -> db.insert(
                "file", null,
                ContentValues().apply {
                    put("folder_id", folderId); put("doc_id", docId); put("rel_path", relPath)
                    put("size", size); put("mtime", mtime); put("seen", generation)
                },
            )
        }
    }

    /** Forgets files that a complete scan did not see any more (our record only; the master keeps its copy). */
    fun forgetUnseen(folderId: Long, generation: Long) {
        writableDatabase.execSQL("DELETE FROM file WHERE folder_id = ? AND seen <> ?", arrayOf(folderId, generation))
    }

    fun inTransaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The oldest files that are due: settled (not modified in the last [settleMs]) and not waiting for a retry. */
    fun pending(now: Long, settleMs: Long, limit: Int): List<Pending> = readableDatabase.rawQuery(
        "SELECT folder_id, doc_id, rel_path, size, mtime, root, chunks, fail_count FROM file " +
            "WHERE state IN (0, 1) AND mtime <= ? AND retry_at <= ? ORDER BY mtime ASC LIMIT $limit",
        arrayOf((now - settleMs).toString(), now.toString()),
    ).use {
        buildList {
            while (it.moveToNext()) {
                add(Pending(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getBlob(5), it.getBlob(6), it.getInt(7)))
            }
        }
    }

    fun hashed(p: Pending, root: ByteArray, chunks: ByteArray, size: Long) {
        writableDatabase.execSQL(
            "UPDATE file SET state = 1, root = ?, chunks = ?, size = ? WHERE folder_id = ? AND doc_id = ?",
            arrayOf(root, chunks, size, p.folderId, p.docId),
        )
    }

    fun uploaded(p: Pending) {
        writableDatabase.execSQL(
            "UPDATE file SET state = 2, fail_count = 0, retry_at = 0, last_error = NULL WHERE folder_id = ? AND doc_id = ?",
            arrayOf(p.folderId, p.docId),
        )
    }

    /**
     * A file that failed: retried after 10 min, 1 h, 1 day, then not again until it changes (the Windows client's schedule).
     * [permanent] skips the retries (a path the master would refuse).
     */
    fun failed(p: Pending, message: String, now: Long, permanent: Boolean = false) {
        val count = p.failCount + 1
        val delay = if (permanent) null else RETRY_MS.getOrNull(count - 1)
        val db = writableDatabase
        if (delay == null) {
            db.execSQL(
                "UPDATE file SET state = 3, fail_count = ?, retry_at = 0, last_error = ?, root = NULL, chunks = NULL WHERE folder_id = ? AND doc_id = ?",
                arrayOf(count, message, p.folderId, p.docId),
            )
        } else {
            db.execSQL(
                "UPDATE file SET fail_count = ?, retry_at = ?, last_error = ?, state = 0, root = NULL, chunks = NULL WHERE folder_id = ? AND doc_id = ?",
                arrayOf(count, now + delay, message, p.folderId, p.docId),
            )
        }
    }

    /** A new master (different database): everything has to be sent again. */
    fun forgetUploaded() {
        writableDatabase.execSQL("UPDATE file SET state = 0, root = NULL, chunks = NULL, fail_count = 0, retry_at = 0, last_error = NULL")
    }

    fun counts(folderId: Long? = null): Counts {
        val where = if (folderId == null) "" else "WHERE folder_id = $folderId"
        return readableDatabase.rawQuery(
            "SELECT SUM(state = 2), SUM(state < 2 AND fail_count = 0), SUM(state = 3 OR (state < 2 AND fail_count > 0)) FROM file $where",
            null,
        ).use { if (it.moveToFirst()) Counts(it.getInt(0), it.getInt(1), it.getInt(2)) else Counts(0, 0, 0) }
    }

    fun failures(limit: Int = 50): List<String> = readableDatabase.rawQuery(
        "SELECT f.prefix, file.rel_path, file.last_error FROM file JOIN folder f ON f.id = file.folder_id " +
            "WHERE file.last_error IS NOT NULL AND file.state <> 2 LIMIT $limit",
        null,
    ).use { buildList { while (it.moveToNext()) add("${it.getString(0)}/${it.getString(1)}\n${it.getString(2)}") } }

    companion object {
        val RETRY_MS = longArrayOf(10 * 60_000L, 60 * 60_000L, 24 * 60 * 60_000L)

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context.applicationContext).also { instance = it }
        }
    }
}
