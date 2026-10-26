package app.fafnirx.android.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.fafnirx.android.data.Db
import app.fafnirx.android.data.Folder
import java.io.FileInputStream
import java.io.InputStream

/**
 * Reading folders the user picked, through the Storage Access Framework. The app only holds a read grant on them, and the only
 * operations used here are queries and read-only opens.
 */
object Saf {
    /** The display name of a picked folder. */
    fun folderName(context: Context, tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "folder"
    }

    fun document(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /**
     * Lists every file under the folder into [db], one query per directory. Hidden names (starting with '.') are skipped: they are
     * the camera's temporary and trash files. Returns false when the folder cannot be read (the grant is gone).
     */
    fun scan(context: Context, db: Db, folder: Folder, generation: Long, stopped: () -> Boolean): Boolean {
        val tree = Uri.parse(folder.treeUri)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to "")
        var ok = true
        try {
            db.inTransaction {
                while (pending.isNotEmpty() && !stopped()) {
                    val (dirId, prefix) = pending.removeLast()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
                    val cursor = context.contentResolver.query(children, columns, null, null, null)
                    if (cursor == null) {
                        ok = false
                        continue
                    }
                    cursor.use {
                        while (it.moveToNext()) {
                            val name = it.getString(1) ?: continue
                            if (name.startsWith(".")) continue
                            val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                            if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                pending.add(it.getString(0) to rel)
                            } else if (it.getInt(5) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                                db.seen(folder.id, it.getString(0), rel, it.getLong(3), it.getLong(4), generation)
                            }
                        }
                    }
                }
                // A listing that was cut short must not make the files it did not reach look gone.
                if (ok && !stopped()) db.forgetUnseen(folder.id, generation)
            }
        } catch (e: SecurityException) {
            ok = false
        }
        return ok
    }

    /** A file in a picked folder. */
    class Source(private val context: Context, private val uri: Uri) : FileSource {
        override fun stream(): InputStream = context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())

        override fun random(): RandomRead {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException(uri.toString())
            val input = FileInputStream(fd.fileDescriptor)
            val channel = input.channel
            return object : RandomRead {
                override fun read(offset: Long, buf: ByteArray, len: Int): Int {
                    channel.position(offset)
                    return Hashing.readFully(input, buf, len)
                }

                override fun close() {
                    input.close()
                    fd.close()
                }
            }
        }

        override fun stat(): Pair<Long, Long>? = try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null }
        } catch (e: Exception) {
            null
        }
    }
}
