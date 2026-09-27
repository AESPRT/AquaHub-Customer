package com.aesprt.aquahub_customer.ui.feature.station

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.aesprt.aquahub_customer.domain.PendingStationLink
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Camera screen used to link a customer account to an AquaHub station QR code. */
class StationQrScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var hasReturnedResult = false
    private var isReadingUploadedImage = false
    private val barcodeScanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }

    private val qrImagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(::scanUploadedQr)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            statusView.text = "Camera access is needed to scan a station QR code."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setContentView(createContentView())

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun createContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(QrFinderOverlayView(this), FrameLayout.LayoutParams(-1, -1))

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD9000B18.toInt(), 0x00000B18),
            )
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        heading.addView(TextView(this).apply {
            text = "Link your station"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        heading.addView(TextView(this).apply {
            text = "Scan an AquaHub station QR code"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setPadding(0, dp(3), 0, 0)
        })
        topBar.addView(heading)

        val close = Button(this).apply {
            text = "Close"
            contentDescription = "Close QR scanner"
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = outlinedRoundedBackground(0x2EFFFFFF, 0x4DFFFFFF, 24)
            setOnClickListener { finish() }
        }
        topBar.addView(close)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2))

        val bottomCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = outlinedRoundedBackground(0xF2071A2B.toInt(), 0x334DD0E1, 28)
        }
        val bottomParams = FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(14), 0, dp(14), dp(14))
        }
        bottomCard.addView(TextView(this).apply {
            text = "Place the QR code inside the frame"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            text = "Keep it steady and make sure the code is well lit."
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setPadding(0, dp(6), 0, dp(14))
        }
        bottomCard.addView(statusView)

        val upload = Button(this).apply {
            text = "Choose QR from gallery"
            contentDescription = "Upload a saved station QR code"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xFF05213A.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(0xFF62D9E8.toInt(), 18)
            setOnClickListener { qrImagePicker.launch("image/*") }
        }
        bottomCard.addView(upload, LinearLayout.LayoutParams(-1, dp(52)))
        bottomCard.addView(TextView(this).apply {
            text = "Camera and gallery scans are processed on this device."
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(bottomCard, bottomParams)
        return root
    }

    private fun scanUploadedQr(uri: Uri) {
        if (hasReturnedResult) return
        isReadingUploadedImage = true
        statusView.text = "Reading QR image…"
        runCatching { InputImage.fromFilePath(this, uri) }
            .onSuccess { image ->
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                    .addOnFailureListener { statusView.text = "Could not read that image. Try a clearer QR code." }
                    .addOnCompleteListener {
                        isReadingUploadedImage = false
                        if (!hasReturnedResult && statusView.text == "Reading QR image…") {
                            statusView.text = "No QR code found. Try another image."
                        }
                    }
            }
            .onFailure {
                isReadingUploadedImage = false
                statusView.text = "Could not open that image. Choose another QR code."
            }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                        analyzeImage(imageProxy)
                    }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                statusView.text = "Unable to start the camera. Please try again."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || hasReturnedResult || isReadingUploadedImage) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        val rawValue = barcodes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotBlank) }
        if (rawValue != null && PendingStationLink.parse(rawValue) != null && !hasReturnedResult) {
            hasReturnedResult = true
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_STATION_LINK, rawValue))
            finish()
        } else if (rawValue != null) {
            runOnUiThread { statusView.text = "That QR code is not an AquaHub station link." }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun outlinedRoundedBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        roundedBackground(fillColor, radiusDp).apply {
            setStroke(dp(1), strokeColor)
        }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STATION_LINK = "extra_station_link"
    }
}

private class QrFinderOverlayView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frameSize = (width.coerceAtMost(height) * 0.64f).coerceAtLeast(220f)
        val left = (width - frameSize) / 2f
        val top = (height - frameSize) / 2f
        val right = left + frameSize
        val bottom = top + frameSize
        paint.style = Paint.Style.FILL
        paint.color = 0x70000000
        canvas.drawRect(0f, 0f, width.toFloat(), top, paint)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, top, left, bottom, paint)
        canvas.drawRect(right, top, width.toFloat(), bottom, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xff4dd0e1.toInt()
        val corner = frameSize * 0.16f
        canvas.drawLine(left, top + corner, left, top, paint)
        canvas.drawLine(left, top, left + corner, top, paint)
        canvas.drawLine(right - corner, top, right, top, paint)
        canvas.drawLine(right, top, right, top + corner, paint)
        canvas.drawLine(left, bottom - corner, left, bottom, paint)
        canvas.drawLine(left, bottom, left + corner, bottom, paint)
        canvas.drawLine(right - corner, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - corner, right, bottom, paint)
    }
}
