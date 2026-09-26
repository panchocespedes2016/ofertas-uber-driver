package com.eu.ofertasevidencia

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Pantalla "Inicio": estado del servicio y activación. */
class MainActivity : Activity() {
    private lateinit var database: EvidenceDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        database = EvidenceDatabase(this)
        prefs = getSharedPreferences(OfferCaptureService.PREFS, MODE_PRIVATE)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.openAccessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        migrateOldScreenshots()
        BottomNav.bind(this, BottomNav.HOME)
    }

    override fun onResume() {
        super.onResume()
        val serviceOn = isCaptureServiceEnabled()
        val autoOn = prefs.getBoolean("auto_enabled", true)
        statusText.text = when {
            serviceOn && autoOn -> "✓ Captura automática activada. Abre Uber Driver y mantén visible la oferta."
            serviceOn -> "Captura automática pausada. Actívala en Config."
            else -> "Captura desactivada. Pulsa el botón y activa “Capturar ofertas de Uber Driver”."
        }
    }

    private fun isCaptureServiceEnabled(): Boolean {
        val expected = ComponentName(this, OfferCaptureService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Migración única: mueve los PNG guardados en el almacenamiento privado
     * (versiones anteriores) a la galería (Pictures/EvidenciaOfertas) y
     * actualiza los registros. No toca los registros que ya están en la galería.
     */
    private fun migrateOldScreenshots() {
        if (prefs.getBoolean("gallery_migrated", false)) return
        var moved = 0
        for (record in database.all()) {
            val path = record.screenshotPath
            if (path.isBlank() || path.startsWith("content://")) continue
            val file = File(path)
            if (!file.exists()) continue
            val uri = ScreenshotFiles.moveFileToGallery(this, file, record.capturedAt) ?: continue
            database.updateScreenshotPath(record.id, uri.toString())
            file.delete()
            moved++
        }
        runCatching { File(filesDir, "evidence").deleteRecursively() }
        prefs.edit().putBoolean("gallery_migrated", true).apply()
        if (moved > 0) {
            Toast.makeText(this, "$moved capturas movidas a la galería", Toast.LENGTH_LONG).show()
        }
    }
}
