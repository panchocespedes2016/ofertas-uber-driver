package com.eu.ofertasevidencia

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EvidenceDatabase(context: Context) : SQLiteOpenHelper(context, "offer_evidence.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE offers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                package_name TEXT NOT NULL,
                summary TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                screenshot_path TEXT NOT NULL,
                screenshot_sha256 TEXT NOT NULL,
                text_sha256 TEXT NOT NULL,
                automatic INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX offers_time_idx ON offers(captured_at DESC)")
        db.execSQL("CREATE INDEX offers_text_hash_idx ON offers(text_sha256)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(record: OfferRecord): Long {
        val values = ContentValues().apply {
            put("captured_at", record.capturedAt)
            put("package_name", record.packageName)
            put("summary", record.summary)
            put("raw_text", record.rawText)
            put("screenshot_path", record.screenshotPath)
            put("screenshot_sha256", record.screenshotSha256)
            put("text_sha256", record.textSha256)
            put("automatic", if (record.automatic) 1 else 0)
        }
        return writableDatabase.insertOrThrow("offers", null, values)
    }

    fun isDuplicate(textHash: String, after: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM offers WHERE text_sha256=? AND captured_at>=? LIMIT 1",
            arrayOf(textHash, after.toString())
        ).use { return it.moveToFirst() }
    }

    fun all(): List<OfferRecord> {
        val result = mutableListOf<OfferRecord>()
        readableDatabase.rawQuery(
            "SELECT id,captured_at,package_name,summary,raw_text,screenshot_path,screenshot_sha256,text_sha256,automatic FROM offers ORDER BY captured_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result += OfferRecord(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getInt(8) == 1
                )
            }
        }
        return result
    }

    fun deleteAll(): List<String> {
        val paths = all().map { it.screenshotPath }
        writableDatabase.delete("offers", null, null)
        return paths
    }

    /** Borra un registro por id. Devuelve la ruta de su captura (o null). */
    fun deleteById(id: Long): String? {
        var path: String? = null
        readableDatabase.rawQuery(
            "SELECT screenshot_path FROM offers WHERE id=?",
            arrayOf(id.toString())
        ).use { if (it.moveToFirst()) path = it.getString(0) }
        writableDatabase.delete("offers", "id=?", arrayOf(id.toString()))
        return path
    }

    /** Actualiza la ruta de la captura de un registro (migración a la galería). */
    fun updateScreenshotPath(id: Long, newPath: String) {
        val values = ContentValues().apply { put("screenshot_path", newPath) }
        writableDatabase.update("offers", values, "id=?", arrayOf(id.toString()))
    }

    /** Completa un registro con el texto extraído por OCR (resumen, texto y hash). */
    fun updateOcrText(id: Long, summary: String, rawText: String, textHash: String) {
        val values = ContentValues().apply {
            put("summary", summary)
            put("raw_text", rawText)
            put("text_sha256", textHash)
        }
        writableDatabase.update("offers", values, "id=?", arrayOf(id.toString()))
    }
}
