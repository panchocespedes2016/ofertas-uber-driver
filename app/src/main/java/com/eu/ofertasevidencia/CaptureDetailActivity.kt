package com.eu.ofertasevidencia

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Vista ampliada de una captura: muestra la imagen en pantalla completa. */
class CaptureDetailActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_detail)

        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<TextView>(R.id.detailTitle).text = title

        val imageView = findViewById<ImageView>(R.id.fullImage)
        // Tocar en cualquier parte cierra la vista ampliada
        findViewById(android.R.id.content).setOnClickListener { finish() }

        if (path.isBlank() || !ScreenshotFiles.exists(this, path)) {
            Toast.makeText(this, "Esta captura no tiene imagen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        try {
            val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
            val display = resources.displayMetrics
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > display.widthPixels ||
                bounds.outHeight / sample > display.heightPixels
            ) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
            } else {
                Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cargar la imagen", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_PATH = "screenshot_path"
        private const val EXTRA_TITLE = "title"

        fun open(caller: Activity, screenshotPath: String, title: String) {
            val intent = Intent(caller, CaptureDetailActivity::class.java).apply {
                putExtra(EXTRA_PATH, screenshotPath)
                putExtra(EXTRA_TITLE, title)
            }
            caller.startActivity(intent)
        }
    }
}
