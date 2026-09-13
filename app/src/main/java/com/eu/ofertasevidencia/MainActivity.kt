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
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var statusText: TextView
    private lateinit var offerList: ListView
    private var records: List<OfferRecord> = emptyList()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        database = EvidenceDatabase(this)
        statusText = findViewById(R.id.statusText)
        offerList = findViewById(R.id.offerList)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { refresh() }
        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }
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
        if (record.screenshotPath.isNotBlank() && File(record.screenshotPath).exists()) {
            builder.setPositiveButton("Compartir captura") { _, _ -> shareScreenshot(record) }
        }
        builder.show()
    }

    private fun shareScreenshot(record: OfferRecord) {
        val file = File(record.screenshotPath)
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
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

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar toda la evidencia?")
            .setMessage("Se eliminarán los registros y las capturas del dispositivo. Esta acción no se puede deshacer.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                database.deleteAll().forEach { path -> if (path.isNotBlank()) File(path).delete() }
                refresh()
            }
            .show()
    }
}
