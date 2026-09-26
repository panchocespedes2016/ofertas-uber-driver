package com.eu.ofertasevidencia

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla "Capturas": lista de evidencias con miniatura, datos y acciones por fila. */
class CapturesActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var countText: TextView
    private lateinit var listView: ListView
    private var records: List<OfferRecord> = emptyList()
    private val dateFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_captures)
        database = EvidenceDatabase(this)
        countText = findViewById(R.id.capturesCount)
        listView = findViewById(R.id.capturesList)
        listView.adapter = CaptureAdapter()
        BottomNav.bind(this, BottomNav.CAPTURES)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        records = database.all()
        countText.text = "${records.size} capturas"
        (listView.adapter as CaptureAdapter).notifyDataSetChanged()
    }

    private inner class CaptureAdapter : BaseAdapter() {
        override fun getCount(): Int = records.size
        override fun getItem(position: Int): OfferRecord = records[position]
        override fun getItemId(position: Int): Long = records[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(this@CapturesActivity).inflate(R.layout.item_captura, parent, false)
            val record = records[position]
            view.findViewById<TextView>(R.id.dateText).text = dateFormat.format(Date(record.capturedAt))
            val badge = view.findViewById<TextView>(R.id.sourceBadge)
            if (record.automatic) {
                badge.text = "Automática"
                badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A6FA5"))
            } else {
                badge.text = "Aceptada"
                badge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2E7D32"))
            }
            view.findViewById<TextView>(R.id.summaryText).text =
                (if (record.screenshotPath.isBlank()) "Solo texto · " else "Contiene · ") + record.summary
            loadThumbnail(record.screenshotPath, view.findViewById(R.id.thumb))
            view.findViewById<ImageButton>(R.id.shareBtn).setOnClickListener { shareCapture(record) }
            view.findViewById<ImageButton>(R.id.deleteBtn).setOnClickListener { confirmDelete(record) }
            return view
        }
    }

    private fun loadThumbnail(path: String, imageView: ImageView) {
        if (path.isBlank() || !ScreenshotFiles.exists(this, path)) {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            return
        }
        try {
            val uri = if (path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) imageView.setImageBitmap(bmp)
            else imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        } catch (e: Exception) {
            imageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun shareCapture(record: OfferRecord) {
        val uri: Uri = ScreenshotFiles.shareableUri(this, record.screenshotPath) ?: run {
            Toast.makeText(this, "Esta captura no tiene imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Oferta registrada: ${dateFormat.format(Date(record.capturedAt))}\nSHA-256: ${record.screenshotSha256}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir evidencia"))
    }

    private fun confirmDelete(record: OfferRecord) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta captura?")
            .setMessage("Se eliminará el registro del ${dateFormat.format(Date(record.capturedAt))} y su imagen de la galería. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                val path = database.deleteById(record.id) ?: record.screenshotPath
                ScreenshotFiles.delete(this, path)
                load()
                Toast.makeText(this, "Captura eliminada", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
