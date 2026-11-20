package com.example.grabratecalc

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvResult: TextView
    private lateinit var etFare: EditText
    private lateinit var etTotalKm: EditText
    private var imageCapture: ImageCapture? = null

    private val operatingCostPerKm = 0.30
    private val targetNetPerKm = 0.60

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else tvResult.text = "Camera permission is required to snap job cards. You can still enter Fare & km manually."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvResult = findViewById(R.id.tvResult)
        etFare = findViewById(R.id.etFare)
        etTotalKm = findViewById(R.id.etTotalKm)
        val btnSnap: Button = findViewById(R.id.btnSnap)
        val btnManualCalc: Button = findViewById(R.id.btnManualCalc)

        btnSnap.setOnClickListener { takePhotoAndRead() }

        btnManualCalc.setOnClickListener {
            val fare = etFare.text.toString().replace(',', '.').toDoubleOrNull()
            val totalKm = etTotalKm.text.toString().replace(',', '.').toDoubleOrNull()

            if (fare == null || totalKm == null || totalKm <= 0.0) {
                tvResult.setBackgroundColor(Color.parseColor("#222222"))
                tvResult.text = "Enter both a valid Fare (RM) and Total km (> 0)."
                return@setOnClickListener
            }

            showProfitVerdict(
                fare = fare,
                totalKm = totalKm,
                breakdown = String.format(Locale.US, "%.2f total", totalKm)
            )
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                tvResult.text = "Camera start failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndRead() {
        val capture = imageCapture ?: return
        tvResult.text = "Scanning..."

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    runOcr(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    tvResult.text = "Capture error: ${exception.message}"
                }
            }
        )
    }

    private fun runOcr(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val raw = visionText.text.replace(',', '.')

                val fareMatch = Regex("""RM\s*([0-9]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
                    .find(raw)
                val fare = fareMatch?.groupValues?.get(1)?.toDoubleOrNull()

                val kmMatches = Regex("""([0-9]+(?:\.[0-9]+)?)\s*km(?!\s*/\s*h)""", RegexOption.IGNORE_CASE)
                    .findAll(raw)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .toList()

                if (fare != null) {
                    etFare.setText(String.format(Locale.US, "%.2f", fare))
                }
                if (kmMatches.size >= 2) {
                    val tripDistances = kmMatches.takeLast(2)
                    val totalKm = tripDistances.sum()
                    etTotalKm.setText(String.format(Locale.US, "%.2f", totalKm))
                }

                if (fare == null || kmMatches.size < 2) {
                    tvResult.setBackgroundColor(Color.parseColor("#222222"))
                    tvResult.text = "Partial/unclear scan—edit boxes below and tap CALC.\nDetected:\n$raw"
                    return@addOnSuccessListener
                }

                val tripDistances = kmMatches.takeLast(2)
                val totalKm = tripDistances.sum()
                val breakdown = tripDistances.joinToString(" + ") {
                    String.format(Locale.US, "%.2f", it)
                }

                showProfitVerdict(fare = fare, totalKm = totalKm, breakdown = "$breakdown km")
            }
            .addOnFailureListener { e ->
                tvResult.text = "OCR failed: ${e.message}"
            }
    }

    private fun showProfitVerdict(fare: Double, totalKm: Double, breakdown: String) {
        val netProfit = fare - (totalKm * operatingCostPerKm)
        val netPerKm = if (totalKm > 0) netProfit / totalKm else 0.0

        val verdict = when {
            netPerKm >= targetNetPerKm -> "ACCEPT"
            netPerKm >= 0.45 -> "BORDERLINE"
            else -> "SKIP"
        }

        tvResult.setBackgroundColor(
            when (verdict) {
                "ACCEPT" -> Color.parseColor("#14532D")
                "BORDERLINE" -> Color.parseColor("#713F12")
                else -> Color.parseColor("#7F1D1D")
            }
        )

        tvResult.text = String.format(
            Locale.US,
            "%s | Net: RM %.2f/km (Profit: RM %.2f)\nFare: RM %.2f | Dist: %.2f km (%s)",
            verdict, netPerKm, netProfit, fare, totalKm, breakdown
        )
    }
}
