package com.eu.ofertasevidencia

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Las capturas se guardan en la galería del teléfono (carpeta "EvidenciaOfertas",
 * visible en Galería y en el explorador de archivos), no en el almacenamiento
 * privado de la app. Los registros antiguos que aún apuntan a rutas de archivo
 * se siguen reconociendo por compatibilidad.
 */
object ScreenshotFiles {
    private const val RELATIVE_DIR = "Pictures/EvidenciaOfertas"

    /** Nombre de archivo: yyyymmdd-hhmmss-auto/manual-exclusivo|match.png */
    fun fileName(capturedAt: Long, automatic: Boolean, isExclusive: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(capturedAt))
        val mode = if (automatic) "auto" else "manual"
        val kind = if (isExclusive) "exclusivo" else "match"
        return "$stamp-$mode-$kind.png"
    }

    /** Guarda el bitmap en la galería. Devuelve el content:// URI o null si falla. */
    fun saveToGallery(context: Context, bitmap: Bitmap, capturedAt: Long, automatic: Boolean, isExclusive: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(capturedAt, automatic, isExclusive))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    /** Mueve un PNG existente (ruta de archivo) a la galería. Devuelve el content:// URI o null. */
    fun moveFileToGallery(context: Context, file: File, capturedAt: Long, automatic: Boolean, isExclusive: Boolean): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(capturedAt, automatic, isExclusive))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } != null
        }.getOrDefault(false)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return uri
    }

    /**
     * Renombra un PNG ya guardado (galería o ruta de archivo) al [displayName] dado.
     * Devuelve la nueva ruta (para la galería, el mismo content:// URI) o null si falla.
     */
    fun rename(context: Context, path: String, displayName: String): String? {
        if (path.isBlank()) return null
        return if (path.startsWith("content://")) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                }
                if (context.contentResolver.update(Uri.parse(path), values, null, null) > 0) path else null
            }.getOrNull()
        } else {
            runCatching {
                val file = File(path)
                val renamed = File(file.parent, displayName)
                if (file.renameTo(renamed)) renamed.absolutePath else null
            }.getOrNull()
        }
    }

    fun exists(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return if (path.startsWith("content://")) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { true } ?: false
            }.getOrDefault(false)
        } else {
            File(path).exists()
        }
    }

    fun delete(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return if (path.startsWith("content://")) {
            runCatching { context.contentResolver.delete(Uri.parse(path), null, null) > 0 }.getOrDefault(false)
        } else {
            runCatching { File(path).delete() }.getOrDefault(false)
        }
    }

    fun readBytes(context: Context, path: String): ByteArray? {
        return runCatching {
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { it.readBytes() }
            } else {
                File(path).readBytes()
            }
        }.getOrNull()
    }

    /** URI listo para compartir con ACTION_SEND (content:// directo o FileProvider). */
    fun shareableUri(context: Context, path: String): Uri? {
        if (path.isBlank()) return null
        return if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            if (!file.exists()) null
            else FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
    }
}
