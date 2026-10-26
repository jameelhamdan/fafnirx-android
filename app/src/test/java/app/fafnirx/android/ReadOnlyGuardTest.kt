package app.fafnirx.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app may only read the phone's storage. This fails the build if the sources or the manifest could write to it.
 * The words are split so that this file does not trigger itself.
 */
class ReadOnlyGuardTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private val forbidden = listOf(
        "create" + "Document", "delete" + "Document", "rename" + "Document", "move" + "Document", "copy" + "Document",
        "FLAG_GRANT_" + "WRITE_URI_PERMISSION", "MANAGE_" + "EXTERNAL_STORAGE", "WRITE_" + "EXTERNAL_STORAGE",
        "READ_" + "EXTERNAL_STORAGE", "READ_" + "MEDIA", "ACCESS_" + "MEDIA_LOCATION", "open" + "OutputStream",
        "contentResolver" + ".delete", "contentResolver" + ".insert", "contentResolver" + ".update",
        "FileOutput" + "Stream", "RandomAccess" + "File", "\"w\"", "\"rw\"", "\"wt\"", "\"wa\"", "\"rwt\"",
    )

    @Test
    fun sourcesNeverWriteToStorage() {
        val files = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }.toList()
        assertTrue(files.size > 5)
        val hits = files.flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals("write access to storage found", emptyList<String>(), hits)
    }

    @Test
    fun filesAreOnlyOpenedForReading() {
        val source = main.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val modes = Regex("""openFileDescriptor\([^,]+,\s*"([^"]*)"\)""").findAll(source).map { it.groupValues[1] }.toList()
        assertTrue(modes.isNotEmpty())
        assertEquals(setOf("r"), modes.toSet())
    }

    @Test
    fun manifestRequestsOnlyTheseNormalPermissions() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val permissions = Regex("""uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
                "android.permission.RECEIVE_BOOT_COMPLETED",
            ),
            permissions,
        )
    }
}
