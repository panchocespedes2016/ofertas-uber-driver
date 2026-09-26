package com.eu.ofertasevidencia

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Las capturas se guardan en la galería del teléfono (carpeta "EvidenciaOfertas",
 * visible en Galería y en el explorador de archivos), no en el almacenamiento
 * privado de la app. Los registros antiguos que aún apuntan a rutas de archivo
 * se siguen reconociendo por compatibilidad.
 */
object ScreenshotFiles {
    private const val RELATIVE_DIR = "Pictures/EvidenciaOfertas"

    /** Guarda el bitmap en la galería. Devuelve el content:// URI o null si falla. */
    fun saveToGallery(context: Context, bitmap: Bitmap, capturedAt: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "oferta_${capturedAt}.png")
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
