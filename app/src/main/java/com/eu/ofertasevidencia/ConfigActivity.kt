package com.eu.ofertasevidencia

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pantalla "Config": ajustes de captura y sistema con el estilo de tarjetas. */
class ConfigActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var autoSwitch: Switch
    private lateinit var bubbleSwitch: Switch
    private lateinit var sizeLabel: TextView
    private lateinit var sizeSeek: SeekBar
    private lateinit var alphaLabel: TextView
    private lateinit var alphaSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        database = EvidenceDatabase(this)
        prefs = getSharedPreferences(OfferCaptureService.PREFS, MODE_PRIVATE)

        autoSwitch = findViewById(R.id.autoSwitch)
        bubbleSwitch = findViewById(R.id.bubbleSwitch)
        sizeLabel = findViewById(R.id.sizeLabel)
        sizeSeek = findViewById(R.id.sizeSeek)
        alphaLabel = findViewById(R.id.alphaLabel)
        alphaSeek = findViewById(R.id.alphaSeek)

        // Los permisos viven en Ajustes del sistema: el interruptor abre la
        // pantalla correspondiente y refleja el estado real al volver.
        autoSwitch.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            autoSwitch.isChecked = isCaptureServiceEnabled()
        }
        bubbleSwitch.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            bubbleSwitch.isChecked = Settings.canDrawOverlays(this)
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

        findViewById<Button>(R.id.exportButton).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { confirmDeleteAll() }

        BottomNav.bind(this, BottomNav.CONFIG)
    }

    override fun onResume() {
        super.onResume()
        autoSwitch.isChecked = isCaptureServiceEnabled()
        bubbleSwitch.isChecked = Settings.canDrawOverlays(this)
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
                database.deleteAll().forEach { path -> ScreenshotFiles.delete(this, path) }
                Toast.makeText(this, "Registros eliminados", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
