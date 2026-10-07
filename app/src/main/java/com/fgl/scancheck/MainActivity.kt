package com.fgl.scancheck

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var targetsText: TextView
    private lateinit var resultBanner: TextView
    private lateinit var lastValueText: TextView
    private lateinit var counterText: TextView
    private lateinit var historyText: TextView
    private lateinit var scanInput: EditText
    private lateinit var previewView: PreviewView
    private lateinit var ocrStatus: TextView
    private lateinit var btnTorch: Button
    private lateinit var roiOverlay: RoiOverlayView

    // ---- settings ----
    private lateinit var settings: ScanSettings
    private var ocrRequireAll = true
    private var ocrFuzzy = false
    private var autoTorch = false
    private var triggerKey = 0
    private var roiEnabled = true
    private var roiW = 0.8f
    private var roiH = 0.3f
    private var roiPos = 0.5f
    private var zoom = 0f

    // Frame position in preview pixels, read by the analyzer thread.
    @Volatile private var roiViewRect: RectF? = null
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    private var intentAction = ""
    private var intentExtra = ""
    private var receiverRegistered = false

    private val prefs by lazy { getSharedPreferences("scan_check", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var toneGen: ToneGenerator? = null

    // ---- results ----
    private var okCount = 0
    private var ngCount = 0
    private val history = ArrayDeque<String>()
    private val foundTargets = mutableSetOf<String>()
    private var lastValue = ""
    private var lastValueTime = 0L
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // ---- camera / OCR ----
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false
    @Volatile private var ocrActive = false
    private val ocrBusy = AtomicBoolean(false)
    private var ocrDeadline = 0L
    private var bestOcrText = ""

    companion object {
        /** How long one press of the trigger keeps trying to read the text. */
        private const val OCR_WINDOW_MS = 1800L
    }

    /** Common extra keys used by handheld scanners when sending a broadcast. */
    private val knownExtraKeys = listOf(
        "com.symbol.datawedge.data_string",
        "com.motorolasolutions.emdk.datawedge.data_string",
        "barcode_string", "SCAN_BARCODE1", "value",
        "barcodeData", "barcode", "data", "scannerdata", "decode_data", "result",
    )

    private val presets = listOf(
        Triple("ไม่ใช้ (รับแบบคีย์บอร์ด)", "", ""),
        Triple("Keyence BT-A500 / BT-A series", "com.fgl.scancheck.SCAN", "data"),
        Triple("Zebra (DataWedge)", "com.fgl.scancheck.SCAN", "com.symbol.datawedge.data_string"),
        Triple("Urovo", "android.intent.ACTION_DECODE_DATA", "barcode_string"),
        Triple("Newland", "nlscan.action.SCANNER_RESULT", "SCAN_BARCODE1"),
        Triple("iData / Kaicom", "android.intent.action.SCANRESULT", "value"),
    )

    private val autoSubmit = Runnable { submitInput() }
    private val ocrTimeout = Runnable { if (ocrActive) finishOcr() }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            readBarcodeFromIntent(intent)?.let { onBarcode(it) }
        }
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else ocrStatus.text = "ไม่ได้อนุญาตกล้อง — อ่านได้เฉพาะบาร์โค้ด"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        targetsText = findViewById(R.id.targetsText)
        resultBanner = findViewById(R.id.resultBanner)
        lastValueText = findViewById(R.id.lastValueText)
        counterText = findViewById(R.id.counterText)
        historyText = findViewById(R.id.historyText)
        scanInput = findViewById(R.id.scanInput)
        previewView = findViewById(R.id.previewView)
        ocrStatus = findViewById(R.id.ocrStatus)
        btnTorch = findViewById(R.id.btnTorch)
        roiOverlay = findViewById(R.id.roiOverlay)
        roiOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRoiSnapshot() }

        cameraExecutor = Executors.newSingleThreadExecutor()
        toneGen = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) } catch (e: Exception) { null }

        loadSettings()
        refreshTargetsLabel()
        refreshCounters()
        refreshOcrHint()
        applyRoiToOverlay()
        setupKeyboardWedge()

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btnOcr).setOnClickListener { startOcr() }
        btnTorch.setOnClickListener { setTorch(!torchOn) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)

        if (settings.targets.isEmpty()) showSettingsDialog()
    }

    override fun onResume() {
        super.onResume()
        registerScanReceiver()
        scanInput.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        unregisterScanReceiver()
        if (ocrActive) cancelOcr()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        recognizer.close()
        toneGen?.release()
    }

    // ---------------- Trigger key → OCR ----------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (triggerKey != 0 && event.keyCode == triggerKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) startOcr()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- Camera ----------------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 960),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(previewSelector).build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                applyZoom()
            } catch (e: Exception) {
                ocrStatus.text = "เปิดกล้องไม่ได้ — อ่านได้เฉพาะบาร์โค้ด"
                Toast.makeText(this, "เปิดกล้องไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(on)
        torchOn = on
        btnTorch.text = if (on) "ไฟ: เปิด" else "ไฟ"
    }

    // ---------------- OCR ----------------

    private fun startOcr() {
        if (camera == null) {
            Toast.makeText(this, "กล้องยังไม่พร้อม", Toast.LENGTH_SHORT).show()
            return
        }
        if (ocrActive) return
        bestOcrText = ""
        ocrDeadline = SystemClock.elapsedRealtime() + OCR_WINDOW_MS
        ocrActive = true
        if (autoTorch && !torchOn) setTorch(true)
        focusOnFrame()
        setBanner("กำลังอ่าน...", "#1565C0")
        lastValueText.text = "เล็งค้างไว้ให้ข้อความอยู่ในกรอบ"
        handler.postDelayed(ocrTimeout, OCR_WINDOW_MS + 400)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (!ocrActive || mediaImage == null || !ocrBusy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        // Size of the picture as ML Kit sees it (upright).
        val imgW = if (rotation == 90 || rotation == 270) proxy.height else proxy.width
        val imgH = if (rotation == 90 || rotation == 270) proxy.width else proxy.height
        val imageRoi = if (roiEnabled) roiInImage(imgW, imgH) else null

        val image = InputImage.fromMediaImage(mediaImage, rotation)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = if (imageRoi == null) result.text else textInside(result, imageRoi)
                onOcrFrame(text)
            }
            .addOnCompleteListener {
                ocrBusy.set(false)
                proxy.close()
            }
    }

    /**
     * Converts the frame (preview pixels) into analysis-image pixels.
     * The preview uses FILL_CENTER, so the picture is scaled to cover the view and centred.
     */
    private fun roiInImage(imgW: Int, imgH: Int): Rect? {
        val roi = roiViewRect ?: return null
        val vw = viewW
        val vh = viewH
        if (vw == 0 || vh == 0 || imgW == 0 || imgH == 0) return null
        val scale = maxOf(vw.toFloat() / imgW, vh.toFloat() / imgH)
        val ox = (vw - imgW * scale) / 2f
        val oy = (vh - imgH * scale) / 2f
        return Rect(
            ((roi.left - ox) / scale).toInt(),
            ((roi.top - oy) / scale).toInt(),
            ((roi.right - ox) / scale).toInt(),
            ((roi.bottom - oy) / scale).toInt(),
        )
    }

    /** Keeps only the words whose centre lies inside [roi]; one output line per text line. */
    private fun textInside(result: com.google.mlkit.vision.text.Text, roi: Rect): String {
        val lines = mutableListOf<String>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val words = line.elements.filter { el ->
                    val b = el.boundingBox
                    b != null && roi.contains(b.centerX(), b.centerY())
                }
                if (words.isNotEmpty()) lines.add(words.joinToString(" ") { it.text })
            }
        }
        return lines.joinToString("\n")
    }

    private fun updateRoiSnapshot() {
        viewW = roiOverlay.width
        viewH = roiOverlay.height
        roiViewRect = if (roiOverlay.width > 0) roiOverlay.roiRect() else null
    }

    private fun applyRoiToOverlay() {
        roiOverlay.roiEnabled = roiEnabled
        roiOverlay.widthFrac = roiW
        roiOverlay.heightFrac = roiH
        roiOverlay.centerYFrac = roiPos
        roiOverlay.post { updateRoiSnapshot() }
    }

    private fun applyZoom() {
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    private fun focusOnFrame() {
        val cam = camera ?: return
        val r = if (roiEnabled && roiOverlay.width > 0) roiOverlay.roiRect()
        else RectF(0f, 0f, previewView.width.toFloat(), previewView.height.toFloat())
        try {
            val point = previewView.meteringPointFactory.createPoint(r.centerX(), r.centerY())
            val action = FocusMeteringAction.Builder(
                point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {
            // Some cameras do not support focus metering; reading still works.
        }
    }

    /** Called on the main thread for every frame read while a trigger press is active. */
    private fun onOcrFrame(text: String) {
        if (!ocrActive) return
        if (text.length > bestOcrText.length) bestOcrText = text
        val r = TextMatcher.check(text, ocrSettings())
        if (r.hasText && r.passed) {
            finishOcr(text)
        } else if (SystemClock.elapsedRealtime() > ocrDeadline) {
            finishOcr()
        }
    }

    /** Ends the current OCR attempt. [passedText] is set when a frame already passed. */
    private fun finishOcr(passedText: String? = null) {
        if (!ocrActive) return
        ocrActive = false
        handler.removeCallbacks(ocrTimeout)
        if (autoTorch) setTorch(false)

        val text = passedText ?: bestOcrText
        val r = TextMatcher.check(text, ocrSettings())
        val firstLine = text.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: ""

        when {
            settings.targets.isEmpty() -> setBanner("ยังไม่ได้ตั้งข้อความ", "#555555")
            !r.hasText -> {
                setBanner("อ่านข้อความไม่ได้", "#EF6C00")
                lastValueText.text = "ลองเล็งใหม่ให้ใกล้และตรงขึ้น"
                beepRetry()
            }
            else -> {
                val found = r.perTarget.filter { it.second }.map { it.first }
                val missing = r.perTarget.filter { !it.second }.map { it.first }
                lastValueText.text = buildString {
                    if (found.isNotEmpty()) append("พบ: ").append(found.joinToString(", "))
                    if (missing.isNotEmpty()) {
                        if (isNotEmpty()) append('\n')
                        append("ไม่พบ: ").append(missing.joinToString(", "))
                    }
                }
                recordResult(r.passed, "[ข้อความ] $firstLine", found)
            }
        }
    }

    private fun cancelOcr() {
        ocrActive = false
        handler.removeCallbacks(ocrTimeout)
        if (autoTorch) setTorch(false)
    }

    private fun ocrSettings() = MatchSettings(
        targets = settings.targets,
        requireAll = ocrRequireAll,
        ignoreCase = settings.ignoreCase,
        ignoreSpace = settings.ignoreSpace,
        fuzzy = ocrFuzzy,
    )

    // ---------------- Barcode input: keyboard wedge ----------------

    private fun setupKeyboardWedge() {
        scanInput.showSoftInputOnFocus = false

        scanInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_TAB
            ) {
                if (event.action == KeyEvent.ACTION_UP) submitInput()
                true
            } else false
        }

        scanInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                actionId == EditorInfo.IME_NULL
            ) {
                submitInput(); true
            } else false
        }

        scanInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(autoSubmit)
                val text = s?.toString() ?: return
                if (text.isEmpty()) return
                if (text.contains('\n') || text.contains('\t') || text.contains('\r')) {
                    submitInput()
                } else {
                    handler.postDelayed(autoSubmit, 400)
                }
            }
        })
    }

    private fun submitInput() {
        handler.removeCallbacks(autoSubmit)
        val value = scanInput.text.toString().replace(Regex("[\\r\\n\\t]"), "").trim()
        scanInput.setText("")
        if (value.isNotEmpty()) onBarcode(value)
    }

    // ---------------- Barcode input: broadcast intent ----------------

    private fun registerScanReceiver() {
        if (receiverRegistered || intentAction.isBlank()) return
        ContextCompat.registerReceiver(
            this, scanReceiver, IntentFilter(intentAction), ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterScanReceiver() {
        if (!receiverRegistered) return
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    @Suppress("DEPRECATION")
    private fun readBarcodeFromIntent(intent: Intent): String? {
        val extras = intent.extras ?: return null
        val keys = if (intentExtra.isNotBlank()) listOf(intentExtra) + knownExtraKeys else knownExtraKeys
        for (k in keys) {
            when (val v = extras.get(k)) {
                is String -> if (v.isNotBlank()) return v.trim()
                is ByteArray -> if (v.isNotEmpty()) return String(v).trim()
            }
        }
        for (k in extras.keySet()) {
            val v = extras.get(k)
            if (v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    // ---------------- Checking ----------------

    private fun onBarcode(raw: String) {
        // The scanner read a barcode: that wins over a running OCR attempt.
        if (ocrActive) cancelOcr()

        val now = SystemClock.elapsedRealtime()
        if (raw == lastValue && now - lastValueTime < 500) return
        lastValue = raw
        lastValueTime = now

        lastValueText.text = raw
        if (settings.targets.isEmpty()) {
            setBanner("ยังไม่ได้ตั้งข้อความ", "#555555")
            return
        }
        val matched = ScanMatcher.match(raw, settings)
        recordResult(matched != null, "[บาร์โค้ด] $raw", listOfNotNull(matched))
    }

    private fun recordResult(ok: Boolean, historyLabel: String, matched: List<String>) {
        val time = timeFmt.format(Date())
        if (ok) {
            okCount++
            foundTargets.addAll(matched)
            val allDone = settings.targets.size > 1 && foundTargets.size == settings.targets.size
            setBanner(if (allDone) "✓ ถูกต้อง (ครบ)" else "✓ ถูกต้อง", "#2E7D32")
            addHistory("$time  ✓  $historyLabel")
            feedbackOk()
        } else {
            ngCount++
            setBanner("✗ ไม่ถูกต้อง", "#C62828")
            addHistory("$time  ✗  $historyLabel")
            feedbackNg()
        }
        refreshCounters()
        refreshTargetsLabel()
    }

    private fun setBanner(text: String, color: String) {
        resultBanner.text = text
        val c = Color.parseColor(color)
        resultBanner.setBackgroundColor(c)
        lastValueText.setBackgroundColor(c)
        roiOverlay.frameColor = if (color == "#555555") Color.WHITE else c
    }

    private fun addHistory(line: String) {
        history.addFirst(line)
        while (history.size > 300) history.removeLast()
        historyText.text = history.joinToString("\n")
    }

    private fun refreshCounters() {
        counterText.text = "ถูกต้อง $okCount   |   ไม่ถูกต้อง $ngCount"
    }

    private fun refreshTargetsLabel() {
        targetsText.text = if (settings.targets.isEmpty()) {
            "ยังไม่ได้ตั้งข้อความที่ถูกต้อง — กด \"ตั้งค่า\""
        } else {
            "ตรวจ: " + settings.targets.joinToString("   ") { t ->
                (if (t in foundTargets) "✓" else "•") + t
            }
        }
    }

    private fun refreshOcrHint() {
        ocrStatus.text = if (triggerKey != 0)
            "เล็งให้ข้อความอยู่ในกรอบ แล้วกดปุ่มยิง"
        else
            "เล็งให้ข้อความอยู่ในกรอบ แล้วกด \"อ่านข้อความ\""
    }

    // ---------------- Feedback ----------------

    @Suppress("DEPRECATION")
    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        else v.vibrate(pattern, -1)
    }

    private fun feedbackOk() {
        toneGen?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        vibrate(longArrayOf(0, 80))
    }

    private fun feedbackNg() {
        toneGen?.startTone(ToneGenerator.TONE_SUP_ERROR, 600)
        vibrate(longArrayOf(0, 250, 120, 250))
    }

    private fun beepRetry() {
        toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 200)
    }

    // ---------------- Settings ----------------

    private fun loadSettings() {
        settings = ScanSettings(
            targets = (prefs.getString("targets", "") ?: "")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            exact = prefs.getBoolean("exact", true),
            ignoreCase = prefs.getBoolean("ignoreCase", true),
            ignoreSpace = prefs.getBoolean("ignoreSpace", false),
        )
        ocrRequireAll = prefs.getBoolean("ocrRequireAll", true)
        ocrFuzzy = prefs.getBoolean("ocrFuzzy", false)
        autoTorch = prefs.getBoolean("autoTorch", false)
        roiEnabled = prefs.getBoolean("roiEnabled", true)
        roiW = prefs.getFloat("roiW", 0.8f)
        roiH = prefs.getFloat("roiH", 0.3f)
        roiPos = prefs.getFloat("roiPos", 0.5f)
        zoom = prefs.getFloat("zoom", 0f)
        triggerKey = prefs.getInt("triggerKey", 0)
        intentAction = prefs.getString("intentAction", "") ?: ""
        intentExtra = prefs.getString("intentExtra", "") ?: ""
    }

    private fun keyLabel(code: Int) =
        if (code == 0) "ปุ่มอ่านข้อความ: ยังไม่ได้ตั้ง (ใช้ปุ่มบนจอแทน)"
        else "ปุ่มอ่านข้อความ: ${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")} ($code)"

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val edit = view.findViewById<EditText>(R.id.editTargets)
        val rExact = view.findViewById<RadioButton>(R.id.radioExact)
        val rContains = view.findViewById<RadioButton>(R.id.radioContains)
        val cCase = view.findViewById<CheckBox>(R.id.chkIgnoreCase)
        val cSpace = view.findViewById<CheckBox>(R.id.chkIgnoreSpace)
        val txtKey = view.findViewById<TextView>(R.id.txtTriggerKey)
        val rAll = view.findViewById<RadioButton>(R.id.radioAll)
        val rAny = view.findViewById<RadioButton>(R.id.radioAny)
        val cFuzzy = view.findViewById<CheckBox>(R.id.chkFuzzy)
        val cTorch = view.findViewById<CheckBox>(R.id.chkAutoTorch)
        val eAction = view.findViewById<EditText>(R.id.editAction)
        val eExtra = view.findViewById<EditText>(R.id.editExtra)
        val cRoi = view.findViewById<CheckBox>(R.id.chkRoi)
        val sW = view.findViewById<SeekBar>(R.id.seekWidth)
        val sH = view.findViewById<SeekBar>(R.id.seekHeight)
        val sPos = view.findViewById<SeekBar>(R.id.seekPos)
        val sZoom = view.findViewById<SeekBar>(R.id.seekZoom)
        val lW = view.findViewById<TextView>(R.id.lblWidth)
        val lH = view.findViewById<TextView>(R.id.lblHeight)
        val lPos = view.findViewById<TextView>(R.id.lblPos)
        val lZoom = view.findViewById<TextView>(R.id.lblZoom)

        var pendingKey = triggerKey
        edit.setText(settings.targets.joinToString("\n"))
        rExact.isChecked = settings.exact
        rContains.isChecked = !settings.exact
        cCase.isChecked = settings.ignoreCase
        cSpace.isChecked = settings.ignoreSpace
        txtKey.text = keyLabel(pendingKey)
        rAll.isChecked = ocrRequireAll
        rAny.isChecked = !ocrRequireAll
        cFuzzy.isChecked = ocrFuzzy
        cTorch.isChecked = autoTorch
        eAction.setText(intentAction)
        eExtra.setText(intentExtra)

        // Frame controls – changes show live on the preview behind the dialog.
        fun frac(bar: SeekBar, min: Int) = maxOf(min, bar.progress) / 100f
        fun refreshFrameLabels() {
            lW.text = "ความกว้างกรอบ: ${(frac(sW, 10) * 100).toInt()}%"
            lH.text = "ความสูงกรอบ: ${(frac(sH, 10) * 100).toInt()}%"
            lPos.text = "ตำแหน่งกรอบ (บน ↔ ล่าง): ${sPos.progress}%"
            lZoom.text = "ซูมกล้อง: ${sZoom.progress}%"
            val on = cRoi.isChecked
            listOf(sW, sH, sPos).forEach { it.isEnabled = on }
        }
        fun previewFrame() {
            roiOverlay.roiEnabled = cRoi.isChecked
            roiOverlay.widthFrac = frac(sW, 10)
            roiOverlay.heightFrac = frac(sH, 10)
            roiOverlay.centerYFrac = sPos.progress / 100f
            camera?.cameraControl?.setLinearZoom(sZoom.progress / 100f)
            refreshFrameLabels()
        }
        cRoi.isChecked = roiEnabled
        sW.progress = (roiW * 100).toInt()
        sH.progress = (roiH * 100).toInt()
        sPos.progress = (roiPos * 100).toInt()
        sZoom.progress = (zoom * 100).toInt()
        refreshFrameLabels()
        val frameListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) previewFrame()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        listOf(sW, sH, sPos, sZoom).forEach { it.setOnSeekBarChangeListener(frameListener) }
        cRoi.setOnCheckedChangeListener { _, _ -> previewFrame() }

        view.findViewById<Button>(R.id.btnLearnKey).setOnClickListener {
            learnTriggerKey { code ->
                pendingKey = code
                txtKey.text = keyLabel(code)
            }
        }

        view.findViewById<Button>(R.id.btnPreset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("ยี่ห้อเครื่องสแกน")
                .setItems(presets.map { it.first }.toTypedArray()) { _, i ->
                    eAction.setText(presets[i].second)
                    eExtra.setText(presets[i].third)
                }
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("ตั้งค่าการตรวจ")
            .setView(view)
            .setPositiveButton("บันทึก") { _, _ ->
                prefs.edit()
                    .putString("targets", edit.text.toString())
                    .putBoolean("exact", rExact.isChecked)
                    .putBoolean("ignoreCase", cCase.isChecked)
                    .putBoolean("ignoreSpace", cSpace.isChecked)
                    .putBoolean("ocrRequireAll", rAll.isChecked)
                    .putBoolean("ocrFuzzy", cFuzzy.isChecked)
                    .putBoolean("autoTorch", cTorch.isChecked)
                    .putInt("triggerKey", pendingKey)
                    .putString("intentAction", eAction.text.toString().trim())
                    .putString("intentExtra", eExtra.text.toString().trim())
                    .putBoolean("roiEnabled", cRoi.isChecked)
                    .putFloat("roiW", frac(sW, 10))
                    .putFloat("roiH", frac(sH, 10))
                    .putFloat("roiPos", sPos.progress / 100f)
                    .putFloat("zoom", sZoom.progress / 100f)
                    .apply()
                unregisterScanReceiver()
                loadSettings()
                registerScanReceiver()
                foundTargets.clear()
                refreshTargetsLabel()
                refreshOcrHint()
            }
            .setNegativeButton("ยกเลิก", null)
            .setOnDismissListener {
                // Show the saved frame (reverts live changes when cancelled).
                applyRoiToOverlay()
                applyZoom()
                scanInput.requestFocus()
            }
            .show()
    }

    /** Waits for the user to press a hardware key and reports its key code. */
    private fun learnTriggerKey(onLearned: (Int) -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("ตั้งปุ่มยิง")
            .setMessage("กดปุ่มที่ต้องการใช้อ่านข้อความ 1 ครั้ง\n(เช่น ปุ่มยิงด้านข้าง หรือปุ่มยิงใต้จอ)")
            .setNeutralButton("ไม่ใช้ปุ่ม") { _, _ -> onLearned(0) }
            .setNegativeButton("ยกเลิก", null)
            .create()
        dialog.setOnKeyListener { d, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_UP) {
                    onLearned(keyCode)
                    d.dismiss()
                }
                true
            }
        }
        dialog.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("ล้างประวัติและตัวนับทั้งหมด?")
            .setPositiveButton("ล้าง") { _, _ ->
                okCount = 0; ngCount = 0
                history.clear(); foundTargets.clear()
                historyText.text = ""
                lastValueText.text = "กดปุ่มยิงลำแสงที่เครื่องสแกน"
                setBanner("พร้อมสแกน", "#555555")
                refreshCounters(); refreshTargetsLabel()
            }
            .setNegativeButton("ยกเลิก", null)
            .setOnDismissListener { scanInput.requestFocus() }
            .show()
    }
}
