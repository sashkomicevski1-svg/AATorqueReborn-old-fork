package com.aatorque.prefs

import com.aatorque.datastore.UserPreference
import java.io.File

/**
 * Named copies of the whole setup (dashboards, theme, font, background, alarms), one file per
 * profile in app storage, in the same format as an export. For a second car, or to try a
 * layout and go back.
 */
class ProfileStore(private val dir: File) {
    companion object {
        private const val EXT = ".pb"
        private val UNSAFE = Regex("[^\\p{L}\\p{M}\\p{N} ._-]")
        private const val MAX_CODE_POINTS = 40

        /**
         * A file-safe name: letters (with their accents), digits, space, dot, dash and
         * underscore, at most 40 characters, never cut in the middle of one.
         */
        fun clean(name: String): String {
            val kept = name.trim().replace(UNSAFE, "")
            val end = kept.offsetByCodePoints(
                0,
                minOf(MAX_CODE_POINTS, kept.codePointCount(0, kept.length))
            )
            return kept.substring(0, end).trim()
        }
    }

    fun list(): List<String> = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(EXT) }
        .map { it.name.removeSuffix(EXT) }
        .sortedBy { it.lowercase() }

    /** Saves [prefs] as [name]; returns the cleaned name, or null when nothing is left of it. */
    fun save(name: String, prefs: UserPreference): String? {
        val clean = clean(name)
        if (clean.isEmpty()) return null
        dir.mkdirs()
        val target = File(dir, clean + EXT)
        val tmp = File(dir, "$clean$EXT.tmp")
        tmp.writeBytes(prefs.toByteArray())
        if (!tmp.renameTo(target)) {
            target.writeBytes(prefs.toByteArray())
            tmp.delete()
        }
        return clean
    }

    fun exists(name: String): Boolean {
        val clean = clean(name)
        return clean.isNotEmpty() && File(dir, clean + EXT).exists()
    }

    fun load(name: String): UserPreference? {
        val file = File(dir, clean(name) + EXT)
        if (!file.exists()) return null
        return file.inputStream().use { UserPreference.parseFrom(it) }
    }

    fun delete(name: String): Boolean = File(dir, clean(name) + EXT).delete()
}
