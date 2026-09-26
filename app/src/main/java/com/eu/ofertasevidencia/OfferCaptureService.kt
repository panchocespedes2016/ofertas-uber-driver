package com.eu.ofertasevidencia

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class OfferCaptureService : AccessibilityService() {
    companion object {
        const val ACTION_MANUAL = "com.eu.ofertasevidencia.CAPTURE_NOW"
        private const val CHANNEL_ID = "capture_status"
        private const val NOTIFICATION_ID = 1107
        const val PREFS = "evidencia_prefs"
        // Zona común del botón Match/Accept medida en capturas reales del S22 Ultra
        // (fracciones del ancho/alto de pantalla: x 25%-90%, y 85%-91%)
        private const val ZONE_X_MIN = 0.25f
        private const val ZONE_X_MAX = 0.90f
        private const val ZONE_Y_MIN = 0.85f
        private const val ZONE_Y_MAX = 0.91f
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var database: EvidenceDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var windowManager: WindowManager
    private var pendingCheck: Runnable? = null
    private var captureInProgress = false
    private var lastForegroundPackage: String? = null

    // Botón flotante
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleVisible = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartX = 0
    private var touchStartY = 0
    private var touchMoved = false

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "bubble_size_dp" || key == "bubble_alpha_pct") applyBubbleAppearance()
            if (key == "bubble_enabled") setBubbleVisible(true)
            if (key == "auto_enabled") showStatusNotification()
        }

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        setBubbleVisible(true)
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPackage = packageName
            // Siempre visible sobre cualquier app mientras el servicio esté activo
            setBubbleVisible(true)
        }
        if (!isUberDriverPackage(packageName)) return
        // La captura automática se puede pausar desde Config sin apagar el servicio
        if (!prefs.getBoolean("auto_enabled", true)) return
        pendingCheck?.let(handler::removeCallbacks)
        pendingCheck = Runnable { inspectCurrentWindow(automatic = true) }.also {
            handler.postDelayed(it, 550)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pendingCheck?.let(handler::removeCallbacks)
        runCatching { unregisterReceiver(manualReceiver) }
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        if (bubbleVisible) runCatching { windowManager.removeView(bubble) }
        bubbleVisible = false
        (getSystemService(NotificationManager::class.java)).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    // ---------- Botón flotante ----------

    private fun ensureBubble() {
        if (bubble != null) return
        val metrics = windowManager.currentWindowMetrics.bounds
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (prefs.getFloat("bubble_x_frac", 0.85f) * metrics.width()).roundToInt()
            y = (prefs.getFloat("bubble_y_frac", 0.70f) * metrics.height()).roundToInt()
        }
        val view = ImageView(this).apply {
            setImageResource(R.drawable.bubble)
            contentDescription = "Capturar oferta y aceptar"
            alpha = prefs.getInt("bubble_alpha_pct", 85) / 100f
            setOnTouchListener { v, e -> onBubbleTouch(v, e, params) }
        }
        bubble = view
        bubbleParams = params
    }

    private fun applyBubbleAppearance() {
        val view = bubble ?: return
        val params = bubbleParams ?: return
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        params.width = sizePx
        params.height = sizePx
        view.alpha = prefs.getInt("bubble_alpha_pct", 85) / 100f
        if (bubbleVisible) runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setBubbleVisible(visible: Boolean) {
        // El usuario puede apagar el botón desde Config sin quitar permisos
        val effective = visible && prefs.getBoolean("bubble_enabled", true)
        if (effective == bubbleVisible) return
        if (effective && !Settings.canDrawOverlays(this)) return
        ensureBubble()
        val view = bubble ?: return
        bubbleVisible = effective
        runCatching {
            if (effective) {
                applyBubbleAppearance()
                windowManager.addView(view, bubbleParams)
            } else {
                windowManager.removeView(view)
            }
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.rawX
                touchDownY = event.rawY
                touchStartX = params.x
                touchStartY = params.y
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchDownX).roundToInt()
                val dy = (event.rawY - touchDownY).roundToInt()
                if (abs(dx) + abs(dy) > 12) touchMoved = true
                if (touchMoved) {
                    params.x = touchStartX + dx
                    params.y = touchStartY + dy
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchMoved) {
                    val metrics = windowManager.currentWindowMetrics.bounds
                    prefs.edit()
                        .putFloat("bubble_x_frac", params.x.toFloat() / metrics.width())
                        .putFloat("bubble_y_frac", params.y.toFloat() / metrics.height())
                        .apply()
                } else {
                    view.performClick()
                    onBubbleTap()
                }
                return true
            }
        }
        return false
    }

    private fun onBubbleTap() {
        // Ocultar el flotante para que no salga en la evidencia
        setBubbleVisible(false)
        handler.postDelayed({
            inspectCurrentWindow(automatic = false) { analysis ->
                if (analysis?.isOffer == true) {
                    tapAcceptRandom()
                } else if (analysis != null) {
                    Toast.makeText(this, "Captura guardada", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Abre una oferta de Uber Driver y toca el botón", Toast.LENGTH_SHORT).show()
                }
                handler.postDelayed({ setBubbleVisible(true) }, 400)
            }
        }, 150)
    }

    /** Toca un punto aleatorio dentro de la zona común del botón Match/Accept. */
    private fun tapAcceptRandom() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val x = (ZONE_X_MIN + Math.random() * (ZONE_X_MAX - ZONE_X_MIN)).toFloat() * w
        val y = (ZONE_Y_MIN + Math.random() * (ZONE_Y_MAX - ZONE_Y_MIN)).toFloat() * h
        val path = Path().apply { moveTo(x, y) }
        val durationMs = (60 + Math.random() * 80).toLong()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post {
                    Toast.makeText(this@OfferCaptureService, "Oferta aceptada", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post {
                    Toast.makeText(this@OfferCaptureService, "No se pudo tocar Aceptar", Toast.LENGTH_SHORT).show()
                }
            }
        }, null)
        if (!dispatched) {
            Toast.makeText(this, "No se pudo tocar Aceptar", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Detección y captura ----------

    private fun inspectCurrentWindow(
        automatic: Boolean,
        onDone: (OfferAnalyzer.Result?) -> Unit = {}
    ) {
        if (captureInProgress) {
            onDone(null)
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            onDone(null)
            return
        }
        val packageName = root.packageName?.toString() ?: ""
        if (!isUberDriverPackage(packageName)) {
            onDone(null)
            return
        }

        val analysis = OfferAnalyzer.analyze(readVisibleText(root))
        if (automatic && !analysis.isOffer) {
            onDone(null)
            return
        }
        if (analysis.normalized.isBlank()) {
            onDone(null)
            return
        }
        if (database.isDuplicate(analysis.hash, System.currentTimeMillis() - 30_000L)) {
            onDone(analysis)
            return
        }

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
                    onDone(analysis)
                }

                override fun onFailure(errorCode: Int) {
                    saveTextOnly(capturedAt, packageName, analysis, automatic)
                    captureInProgress = false
                    onDone(analysis)
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
            // Carpeta visible en la galería; si falla, reserva en almacenamiento privado.
            val galleryUri = ScreenshotFiles.saveToGallery(this, bitmap, capturedAt)
            val path: String
            val imageHash: String
            if (galleryUri != null) {
                path = galleryUri.toString()
                imageHash = OfferAnalyzer.sha256(ScreenshotFiles.readBytes(this, path) ?: ByteArray(0))
            } else {
                val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(capturedAt))
                val dir = File(filesDir, "evidence/$day").apply { mkdirs() }
                val file = File(dir, "oferta_${capturedAt}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                path = file.absolutePath
                imageHash = OfferAnalyzer.sha256(file.readBytes())
            }
            database.insert(
                OfferRecord(0, capturedAt, packageName, analysis.summary, analysis.normalized,
                    path, imageHash, analysis.hash, automatic)
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

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    // ---------- Notificación ----------

    private fun showStatusNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Estado de captura", NotificationManager.IMPORTANCE_LOW)
        )
        val actionIntent = Intent(ACTION_MANUAL).setPackage(packageName)
        val action = PendingIntent.getBroadcast(
            this, 7, actionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val autoOn = prefs.getBoolean("auto_enabled", true)
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.eu.ofertasevidencia.R.drawable.ic_evidence)
            .setContentTitle(if (autoOn) "Captura de ofertas activa" else "Captura automática pausada")
            .setContentText("Las evidencias se guardan solo en este dispositivo")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "Capturar ahora", action)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
