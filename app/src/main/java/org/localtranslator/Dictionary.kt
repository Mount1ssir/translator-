package org.localtranslator

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.text.Normalizer
import java.util.Locale
import java.util.zip.GZIPInputStream

internal class Dictionary(private val context: Context) : AutoCloseable {
    data class Sense(val word: String, val part: String, val definition: String, val example: String, val extended: Boolean)
    data class Result(val senses: List<Sense>, val suggestions: List<String> = emptyList())

    private val database = lazy {
        val file = context.filesDir.resolve("dictionary-v2.db")
        if (!file.exists()) {
            val temporary = java.io.File.createTempFile("dictionary-v2-", ".tmp", context.cacheDir)
            try {
                context.assets.open("dictionary.db.gzip").use { asset ->
                    GZIPInputStream(asset).use { source ->
                        temporary.outputStream().use { source.copyTo(it) }
                    }
                }
                check(temporary.renameTo(file) || file.exists())
            } finally {
                temporary.delete()
            }
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also {
            context.filesDir.resolve("dictionary-v1.db").delete()
        }
    }

    fun find(text: String): Result {
        val key = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace('’', '\'')

        if (key.isEmpty()) return Result(emptyList())
        val db = database.value
        val columns = "word, pos, definition, example, source"
        val exactQuery = "SELECT DISTINCT $columns FROM senses WHERE key = ? ORDER BY source, rowid LIMIT 20"
        val exact = read(db, exactQuery, key)
        if (exact.isNotEmpty()) return Result(exact)
        val trimmed = key.trim { it.isWhitespace() || it in ".,!?;:\"“”()[]{}" }
        if (trimmed.isEmpty()) return Result(emptyList())
        if (trimmed != key) {
            val unpunctuated = read(db, exactQuery, trimmed)
            if (unpunctuated.isNotEmpty()) return Result(unpunctuated)
        }
        val inflected = read(db, """
            SELECT DISTINCT $columns FROM senses
            WHERE key IN (SELECT lemma FROM forms WHERE key = ?)
            ORDER BY source, rowid LIMIT 20
        """.trimIndent(), trimmed)
        if (inflected.isNotEmpty()) return Result(inflected)
        val suggestions = db.rawQuery(
            "SELECT DISTINCT key FROM senses WHERE key >= ? AND key < ? ORDER BY key LIMIT 8",
            arrayOf(trimmed, trimmed + "\uffff")
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return Result(emptyList(), suggestions)
    }

    private fun read(db: SQLiteDatabase, query: String, key: String): List<Sense> =
        db.rawQuery(query, arrayOf(key)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Sense(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getInt(4) == 1))
                }
            }
        }

    override fun close() {
        if (database.isInitialized()) database.value.close()
    }
}
