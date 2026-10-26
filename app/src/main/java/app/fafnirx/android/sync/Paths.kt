package app.fafnirx.android.sync

import java.text.Normalizer

/** The master's path rules (`clean_remote_path` in `fafnirx-common`): relative, '/' separated, NFC, no odd components. */
object Paths {
    /** The cleaned path, or null when the master would refuse it. */
    fun clean(path: String): String? {
        val p = Normalizer.normalize(path, Normalizer.Form.NFC)
        if (p.isEmpty() || p.toByteArray().size > 4096) return null
        if (p.startsWith("/") || p.contains('\\')) return null
        for (part in p.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") return null
            if (part.any { Character.isISOControl(it) }) return null
            if (part.toByteArray().size > 255) return null
        }
        return p
    }

    /** A folder name made usable as the first component of a remote path. */
    fun folderName(name: String): String {
        var n = name.map { if (it == '/' || it == '\\' || Character.isISOControl(it)) '_' else it }.joinToString("").trim()
        if (n.isEmpty() || n == "." || n == "..") n = "folder"
        while (n.toByteArray().size > 200) n = n.dropLast(1)
        return Normalizer.normalize(n, Normalizer.Form.NFC)
    }

    /** [name] if free, else name-2, name-3, ... The choice is stored with the folder, so it is made once. */
    fun uniquePrefix(name: String, taken: Set<String>): String {
        val base = folderName(name)
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }
}
