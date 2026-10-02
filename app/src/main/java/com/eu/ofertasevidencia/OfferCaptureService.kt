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
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

    // Botón flotante: contenedor horizontal con el icono y (al aceptar) las métricas $/h y $/mi
    private var bubbleLayout: LinearLayout? = null
    private var bubbleIcon: ImageView? = null
    private var bubbleMetrics: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleVisible = false
    private var bubbleExpanded = false
    private var bubbleExpandedLeft = false
    private var bubbleExpandedExtra = 0

    // OCR en el teléfono: lee la oferta de la imagen porque Uber no expone texto
    private val ocrClient by lazy { TextRecognition.getClient(TextRecognizerOptions.Builder().build()) }

    private fun recognizeText(bitmap: Bitmap, onResult: (String) -> Unit) {
        runCatching {
            ocrClient.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { visionText -> onResult(offerCardText(visionText)) }
                .addOnFailureListener { onResult("") }
        }.onFailure { onResult("") }
    }

    // Anclas de la tarjeta de oferta: la insignia "UberX" marca el borde superior
    // y el botón "Accept"/"Aceptar" el borde inferior. Todo lo que esté fuera
    // (p. ej. los textos del mapa) se ignora.
    private val offerTopAnchor = Regex("\\bUber[A-Za-z]*\\b", RegexOption.IGNORE_CASE)
    private val offerBottomAnchor = Regex("\\b(Accept|Aceptar)\\b", RegexOption.IGNORE_CASE)

    /**
     * Devuelve solo el texto dentro de la tarjeta de oferta: desde la insignia
     * "UberX" hasta el botón "Accept". Si no encuentra las anclas, devuelve el
     * texto completo (comportamiento anterior) para no perder datos.
     */
    private fun offerCardText(visionText: Text): String {
        val blocks = visionText.textBlocks
        if (blocks.isEmpty()) return visionText.text
        var topY = -1
        var bottomY = -1
        for (block in blocks) {
            val box = block.boundingBox ?: continue
            if (topY < 0 && offerTopAnchor.containsMatchIn(block.text)) topY = box.top
            if (offerBottomAnchor.containsMatchIn(block.text)) {
                if (box.bottom > bottomY) bottomY = box.bottom
            }
        }
        if (topY < 0) return visionText.text
        val margin = 8
        val kept = blocks.filter { block ->
            val box = block.boundingBox ?: return@filter false
            box.top >= topY - margin && (bottomY < 0 || box.top <= bottomY + margin)
        }
        return kept.joinToString("\n") { it.text }.ifBlank { visionText.text }
    }
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchStartX = 0
    private var touchStartY = 0
    private var touchMoved = false
    private var touchSwallowed = false

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
        handler.removeCallbacks(collapseBubbleRunnable)
        runCatching { unregisterReceiver(manualReceiver) }
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        if (bubbleVisible) runCatching { windowManager.removeView(bubbleLayout) }
        bubbleVisible = false
        (getSystemService(NotificationManager::class.java)).cancel(NOTIFICATION_ID)
        runCatching { ocrClient.close() }
        super.onDestroy()
    }

    // ---------- Botón flotante ----------

    private fun ensureBubble() {
        if (bubbleLayout != null) return
        val screen = windowManager.currentWindowMetrics.bounds
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (prefs.getFloat("bubble_x_frac", 0.85f) * screen.width()).roundToInt()
            y = (prefs.getFloat("bubble_y_frac", 0.70f) * screen.height()).roundToInt()
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.bubble)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        }
        val metricsView = TextView(this).apply {
            setBackgroundResource(R.drawable.bubble_pill)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, sizePx
            )
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Capturar oferta y aceptar"
            alpha = prefs.getInt("bubble_alpha_pct", 85) / 100f
            addView(icon)
            setOnTouchListener { v, e -> onBubbleTouch(v, e, params) }
        }
        bubbleLayout = layout
        bubbleIcon = icon
        bubbleMetrics = metricsView
        bubbleParams = params
    }

    private fun applyBubbleAppearance() {
        val layout = bubbleLayout ?: return
        val params = bubbleParams ?: return
        // Si el tamaño o la opacidad cambian, el botón vuelve a su forma compacta
        collapseBubble()
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        params.width = sizePx
        params.height = sizePx
        layout.alpha = prefs.getInt("bubble_alpha_pct", 85) / 100f
        bubbleIcon?.layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
        if (bubbleVisible) runCatching { windowManager.updateViewLayout(layout, params) }
    }

    private fun setBubbleVisible(visible: Boolean) {
        // El usuario puede apagar el botón desde Config sin quitar permisos
        val effective = visible && prefs.getBoolean("bubble_enabled", true)
        if (effective == bubbleVisible) return
        if (effective && !Settings.canDrawOverlays(this)) return
        ensureBubble()
        val layout = bubbleLayout ?: return
        collapseBubble()
        bubbleVisible = effective
        runCatching {
            if (effective) {
                applyBubbleAppearance()
                windowManager.addView(layout, bubbleParams)
            } else {
                windowManager.removeView(layout)
            }
        }
    }

    private fun onBubbleTouch(view: View, event: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Si está alargado mostrando métricas, este toque solo lo colapsa
                if (bubbleExpanded) {
                    collapseBubble()
                    touchSwallowed = true
                    return true
                }
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
                if (touchSwallowed) {
                    touchSwallowed = false
                    return true
                }
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

    private val collapseBubbleRunnable = Runnable { collapseBubble() }

    /**
     * Alarga el botón hacia el lado con más espacio y muestra $/h y $/mi.
     * Se colapsa solo a los 6 segundos o al tocarlo.
     */
    private fun showBubbleMetrics(perHour: Double, perMile: Double) {
        val layout = bubbleLayout ?: return
        val params = bubbleParams ?: return
        val metricsView = bubbleMetrics ?: return
        val icon = bubbleIcon ?: return
        if (!bubbleVisible) return
        collapseBubble()
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        metricsView.text = String.format(Locale.US, "$/h %.2f   $/mi %.2f", perHour, perMile)
        metricsView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillWidth = metricsView.measuredWidth + sizePx + dpToPx(8)
        val screenW = windowManager.currentWindowMetrics.bounds.width()
        // Crece hacia el lado con más espacio para no salirse de la pantalla
        val expandLeft = params.x + sizePx / 2 > screenW / 2
        layout.removeAllViews()
        if (expandLeft) {
            layout.addView(metricsView)
            layout.addView(icon)
            params.x = (params.x - (pillWidth - sizePx)).coerceAtLeast(0)
        } else {
            layout.addView(icon)
            layout.addView(metricsView)
        }
        metricsView.visibility = View.VISIBLE
        params.width = pillWidth
        params.height = sizePx
        bubbleExpanded = true
        bubbleExpandedLeft = expandLeft
        bubbleExpandedExtra = pillWidth - sizePx
        runCatching { windowManager.updateViewLayout(layout, params) }
        handler.removeCallbacks(collapseBubbleRunnable)
        handler.postDelayed(collapseBubbleRunnable, 6000)
    }

    private fun collapseBubble() {
        if (!bubbleExpanded) return
        bubbleExpanded = false
        handler.removeCallbacks(collapseBubbleRunnable)
        val layout = bubbleLayout ?: return
        val params = bubbleParams ?: return
        val icon = bubbleIcon ?: return
        val metricsView = bubbleMetrics ?: return
        val sizePx = dpToPx(prefs.getInt("bubble_size_dp", 56))
        metricsView.visibility = View.GONE
        layout.removeAllViews()
        layout.addView(icon)
        if (bubbleExpandedLeft) params.x += bubbleExpandedExtra
        params.width = sizePx
        params.height = sizePx
        if (bubbleVisible) runCatching { windowManager.updateViewLayout(layout, params) }
    }

    private fun onBubbleTap() {
        // Ocultar el flotante para que no salga en la evidencia.
        // El toque en la zona de Aceptar se dispara siempre dentro de inspect,
        // sin verificar si es oferta: Uber pinta todo como imagen.
        setBubbleVisible(false)
        handler.postDelayed({
            inspectCurrentWindow(automatic = false) {
                // El botón vuelve 400 ms después de que el toque ya se disparó
                handler.postDelayed({ setBubbleVisible(true) }, 400)
            }
        }, 150)
    }

    /** $/h y $/mi a partir del análisis, o null si faltan datos. */
    private fun offerMetrics(analysis: OfferAnalyzer.Result): Pair<Double, Double>? {
        val price = analysis.price ?: return null
        val minutes = analysis.totalMinutes?.takeIf { it > 0 } ?: return null
        val miles = analysis.totalMiles?.takeIf { it > 0 } ?: return null
        return Pair(price / (minutes / 60.0), price / miles)
    }

    /** Toca un punto aleatorio dentro de la zona común del botón Match/Accept. */
    private fun tapAcceptRandom() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val x = (ZONE_X_MIN + Math.random() * (ZONE_X_MAX - ZONE_X_MIN)).toFloat() * w
        val y = (ZONE_Y_MIN + Math.random() * (ZONE_Y_MAX - ZONE_Y_MIN)).toFloat() * h
        val path = Path().apply { moveTo(x, y) }
        // Duración variable como un toque humano: nunca es exactamente igual
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

    /**
     * Flujo con OCR (Uber pinta la oferta como imagen, no expone texto):
     * - Automática: captura + OCR; solo guarda si el texto reconocido parece oferta.
     * - Manual: SIEMPRE guarda la captura y SIEMPRE toca la zona de Aceptar,
     *   sin verificar si es oferta. El OCR corre en paralelo para las métricas.
     */
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
        // La captura automática solo corre dentro de Uber Driver.
        // El botón manual actúa siempre, esté en la app que esté.
        if (automatic && !isUberDriverPackage(packageName)) {
            onDone(null)
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
                        captureInProgress = false
                        if (!automatic) {
                            tapAcceptRandom()
                            Toast.makeText(this@OfferCaptureService, "Oferta aceptada", Toast.LENGTH_SHORT).show()
                        }
                        onDone(null)
                        return
                    }
                    if (automatic) {
                        recognizeText(bitmap) { ocrText ->
                            try {
                                val analysis = OfferAnalyzer.analyze(listOf(ocrText))
                                if (!analysis.isOffer || analysis.normalized.isBlank()) {
                                    onDone(null)
                                    return@recognizeText
                                }
                                if (database.isDuplicate(analysis.hash, System.currentTimeMillis() - 30_000L)) {
                                    onDone(analysis)
                                    return@recognizeText
                                }
                                saveEvidence(bitmap, capturedAt, packageName, analysis, true)
                                onDone(analysis)
                            } finally {
                                bitmap.recycle()
                                captureInProgress = false
                            }
                        }
                    } else {
                        // Manual: guardar ya, tocar ya; el OCR enriquece el registro en paralelo
                        val placeholder = OfferAnalyzer.Result(
                            isOffer = false, normalized = "", summary = "",
                            hash = OfferAnalyzer.sha256("manual-$capturedAt".toByteArray())
                        )
                        val id = saveEvidence(bitmap, capturedAt, packageName, placeholder, false)
                        captureInProgress = false
                        tapAcceptRandom()
                        Toast.makeText(this@OfferCaptureService, "Oferta aceptada", Toast.LENGTH_SHORT).show()
                        onDone(placeholder)
                        if (id == -1L) {
                            bitmap.recycle()
                        } else {
                            recognizeText(bitmap) { ocrText ->
                                try {
                                    if (ocrText.isNotBlank()) {
                                        val analysis = OfferAnalyzer.analyze(listOf(ocrText))
                                        if (database.isDuplicate(analysis.hash, System.currentTimeMillis() - 30_000L)) {
                                            // La misma oferta ya se guardó hace segundos: quitar el duplicado
                                            database.deleteById(id)?.let { path ->
                                                ScreenshotFiles.delete(this@OfferCaptureService, path)
                                            }
                                        } else {
                                            database.updateOcrText(id, analysis.summary, ocrText, analysis.hash)
                                        }
                                        offerMetrics(analysis)?.let { (perHour, perMile) ->
                                            handler.post { showBubbleMetrics(perHour, perMile) }
                                            handler.postDelayed({ showBubbleMetrics(perHour, perMile) }, 1500)
                                        }
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    captureInProgress = false
                    // 0=error interno (p. ej. FLAG_SECURE), 1=capturas muy seguidas, 2=display inválido
                    android.util.Log.e("OfferCapture", "takeScreenshot falló, error=$errorCode")
                    handler.post {
                        Toast.makeText(
                            this@OfferCaptureService,
                            "Captura falló (error $errorCode)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (!automatic) {
                        tapAcceptRandom()
                        Toast.makeText(this@OfferCaptureService, "Oferta aceptada", Toast.LENGTH_SHORT).show()
                    }
                    onDone(null)
                }
            }
        )
    }

    /** Guarda la captura y devuelve el id del registro, o -1 si falló. */
    private fun saveEvidence(
        bitmap: Bitmap,
        capturedAt: Long,
        packageName: String,
        analysis: OfferAnalyzer.Result,
        automatic: Boolean
    ): Long {
        return runCatching {
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
        }.getOrElse { -1L }
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
