package com.eu.ofertasevidencia

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfferCaptureService : AccessibilityService() {
    companion object {
        const val ACTION_MANUAL = "com.eu.ofertasevidencia.CAPTURE_NOW"
        private const val CHANNEL_ID = "capture_status"
        private const val NOTIFICATION_ID = 1107
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var database: EvidenceDatabase
    private var pendingCheck: Runnable? = null
    private var captureInProgress = false

    private val manualReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MANUAL) {
                handler.postDelayed({ inspectCurrentWindow(automatic = false) }, 700)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        database = EvidenceDatabase(this)
        val filter = IntentFilter(ACTION_MANUAL)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(manualReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(manualReceiver, filter)
        }
        showStatusNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (!isUberDriverPackage(packageName)) return
        pendingCheck?.let(handler::removeCallbacks)
        pendingCheck = Runnable { inspectCurrentWindow(automatic = true) }.also {
            handler.postDelayed(it, 550)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingCheck?.let(handler::removeCallbacks)
        runCatching { unregisterReceiver(manualReceiver) }
        (getSystemService(NotificationManager::class.java)).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun inspectCurrentWindow(automatic: Boolean) {
        if (captureInProgress) return
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        if (!isUberDriverPackage(packageName)) return

        val analysis = OfferAnalyzer.analyze(readVisibleText(root))
        if (automatic && !analysis.isOffer) return
        if (analysis.normalized.isBlank()) return
        if (database.isDuplicate(analysis.hash, System.currentTimeMillis() - 30_000L)) return

        captureInProgress = true
        val capturedAt = System.currentTimeMillis()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        buffer.close()
                    }
                    if (bitmap == null) {
                        saveTextOnly(capturedAt, packageName, analysis, automatic)
                    } else {
                        saveEvidence(bitmap, capturedAt, packageName, analysis, automatic)
                        bitmap.recycle()
                    }
                    captureInProgress = false
                }

                override fun onFailure(errorCode: Int) {
                    saveTextOnly(capturedAt, packageName, analysis, automatic)
                    captureInProgress = false
                }
            }
        )
    }

    private fun readVisibleText(root: AccessibilityNodeInfo): List<String> {
        val values = linkedSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 700) {
            val node = queue.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(values::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return values.toList()
    }

    private fun saveEvidence(
        bitmap: Bitmap,
        capturedAt: Long,
        packageName: String,
        analysis: OfferAnalyzer.Result,
        automatic: Boolean
    ) {
        runCatching {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(capturedAt))
            val dir = File(filesDir, "evidence/$day").apply { mkdirs() }
            val file = File(dir, "oferta_${capturedAt}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val imageHash = OfferAnalyzer.sha256(file.readBytes())
            database.insert(
                OfferRecord(0, capturedAt, packageName, analysis.summary, analysis.normalized,
                    file.absolutePath, imageHash, analysis.hash, automatic)
            )
        }.onFailure {
            saveTextOnly(capturedAt, packageName, analysis, automatic)
        }
    }

    private fun saveTextOnly(
        capturedAt: Long,
        packageName: String,
        analysis: OfferAnalyzer.Result,
        automatic: Boolean
    ) {
        runCatching {
            database.insert(
                OfferRecord(0, capturedAt, packageName, analysis.summary, analysis.normalized,
                    "", "", analysis.hash, automatic)
            )
        }
    }

    private fun isUberDriverPackage(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains("uber") && (lower.contains("driver") || lower.contains("cab"))
    }

    private fun showStatusNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Estado de captura", NotificationManager.IMPORTANCE_LOW)
        )
        val actionIntent = Intent(ACTION_MANUAL).setPackage(packageName)
        val action = PendingIntent.getBroadcast(
            this, 7, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.eu.ofertasevidencia.R.drawable.ic_evidence)
            .setContentTitle("Captura de ofertas activa")
            .setContentText("Las evidencias se guardan solo en este dispositivo")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "Capturar ahora", action)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
