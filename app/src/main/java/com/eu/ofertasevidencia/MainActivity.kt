package com.eu.ofertasevidencia

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences
import android.widget.SeekBar
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var overlayButton: Button
    private lateinit var sizeLabel: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var alphaLabel: TextView
    private lateinit var alphaSeek: SeekBar
    private lateinit var offerList: ListView
    private var records: List<OfferRecord> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        database = EvidenceDatabase(this)
        prefs = getSharedPreferences(OfferCaptureService.PREFS, MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)
        overlayButton = findViewById(R.id.overlayButton)
        sizeLabel = findViewById(R.id.sizeLabel)
        sizeSeek = findViewById(R.id.sizeSeek)
        alphaLabel = findViewById(R.id.alphaLabel)
        alphaSeek = findViewById(R.id.alphaSeek)
        offerList = findViewById(R.id.offerList)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        overlayButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val dp = 32 + progress
                prefs.edit().putInt("bubble_size_dp", dp).apply()
                sizeLabel.text = "Tamaño del botón flotante: ${dp}dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pct = 30 + progress
                prefs.edit().putInt("bubble_alpha_pct", pct).apply()
                alphaLabel.text = "Opacidad del botón flotante: ${pct}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.capturesButton).setOnClickListener {
            startActivity(Intent(this, CapturesActivity::class.java))
        }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { confirmDeleteAll() }
        offerList.setOnItemClickListener { _, _, position, _ -> showRecord(records[position]) }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 33)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isCaptureServiceEnabled()
        statusText.text = if (enabled) {
            "✓ Captura automática activada. Abre Uber Driver y mantén visible la oferta."
        } else {
            "Captura desactivada. Pulsa el botón y activa “Capturar ofertas de Uber Driver”."
        }
        records = database.all()
        val rows = records.map { record ->
            mapOf(
                "line1" to dateFormat.format(Date(record.capturedAt)),
                "line2" to (if (record.screenshotPath.isBlank()) "Solo texto · " else "Captura + texto · ") + record.summary
            )
        }
        offerList.adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_2,
            arrayOf("line1", "line2"), intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        refreshBubbleSettings()
    }

    private fun refreshBubbleSettings() {
        overlayButton.text = if (Settings.canDrawOverlays(this)) {
            "✓ Botón flotante permitido"
        } else {
            "Permitir botón flotante sobre Uber"
        }
        val sizeDp = prefs.getInt("bubble_size_dp", 56)
        sizeSeek.progress = sizeDp - 32
        sizeLabel.text = "Tamaño del botón flotante: ${sizeDp}dp"
        val alphaPct = prefs.getInt("bubble_alpha_pct", 85)
        alphaSeek.progress = alphaPct - 30
        alphaLabel.text = "Opacidad del botón flotante: ${alphaPct}%"
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val expected = ComponentName(this, OfferCaptureService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showRecord(record: OfferRecord) {
        val details = buildString {
            append(dateFormat.format(Date(record.capturedAt)))
            append("\n\n")
            append(record.rawText)
            append("\n\nHuella del texto: ").append(record.textSha256)
            if (record.screenshotSha256.isNotBlank()) {
                append("\nHuella de la imagen: ").append(record.screenshotSha256)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Oferta registrada")
            .setMessage(details)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Borrar") { _, _ -> confirmDeleteRecord(record) }
        if (record.screenshotPath.isNotBlank() && ScreenshotFiles.exists(this, record.screenshotPath)) {
            builder.setPositiveButton("Compartir captura") { _, _ -> shareScreenshot(record) }
        }
        builder.show()
    }

    private fun shareScreenshot(record: OfferRecord) {
        val uri: Uri = ScreenshotFiles.shareableUri(this, record.screenshotPath) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Oferta registrada: ${dateFormat.format(Date(record.capturedAt))}\nSHA-256: ${record.screenshotSha256}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir evidencia"))
    }

    private fun exportCsv() {
        val current = database.all()
        if (current.isEmpty()) {
            Toast.makeText(this, "Todavía no hay ofertas registradas", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "ofertas_${System.currentTimeMillis()}.csv")
        file.bufferedWriter(Charsets.UTF_8).use { out ->
            out.write("fecha_iso,paquete,automatico,resumen,texto,ruta_captura,sha256_imagen,sha256_texto\n")
            for (record in current) {
                val values = listOf(
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date(record.capturedAt)),
                    record.packageName, record.automatic.toString(), record.summary, record.rawText,
                    record.screenshotPath, record.screenshotSha256, record.textSha256
                )
                out.write(values.joinToString(",") { csv(it) })
                out.newLine()
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Exportar registros"))
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun confirmDeleteRecord(record: OfferRecord) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta captura?")
            .setMessage("Se eliminará el registro del ${dateFormat.format(Date(record.capturedAt))} y su imagen de la galería. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                val path = database.deleteById(record.id) ?: record.screenshotPath
                ScreenshotFiles.delete(this, path)
                refresh()
                Toast.makeText(this, "Captura eliminada", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar toda la evidencia?")
            .setMessage("Se eliminarán los registros y las capturas del dispositivo. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                database.deleteAll().forEach { path -> ScreenshotFiles.delete(this, path) }
                refresh()
            }
            .show()
    }
}
