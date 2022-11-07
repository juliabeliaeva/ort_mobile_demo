package org.jetbrains.kotlinx.dl.example.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.kotlinx.dl.api.inference.objectdetection.DetectedObject
import java.lang.RuntimeException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private val backgroundExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    @Volatile
    private lateinit var cameraProcessor: CameraProcessor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val modelsSpinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Pipelines.values().map { it.name }
        )
        modelsSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val modelSelectedListener = ModelItemSelectedListener(this)

        with(models) {
            adapter = modelsSpinnerAdapter
            onItemSelectedListener = modelSelectedListener
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        detector_view.scaleType = viewFinder.scaleType
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val imageAnalyzer = ImageAnalyzer(applicationContext, resources, ::updateUI)
            cameraProcessor = CameraProcessor(
                imageAnalyzer,
                cameraProviderFuture.get(),
                viewFinder.surfaceProvider,
                backgroundExecutor
            )
            if (!cameraProcessor.bindCameraUseCases(this)) {
                showError("Could not initialize camera.")
            }

            runOnUiThread {
                models.setSelection(0, false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showError("Permissions not granted by the user.")
            }
        }
    }

    private fun showError(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateUI(result: AnalysisResult?) {
        runOnUiThread {
            clearUi()
            if (result == null || result.confidence < 0.5f) {
                detector_view.setDetection(null)
                return@runOnUiThread
            }
            detector_view.setDetection(result)
            percentMeter.progress = (result.confidence * 100).toInt()
            val (item, value) = when (val prediction = result.prediction) {
                is String -> prediction to "%.2f%%".format(result.confidence * 100)
                is DetectedObject -> prediction.label to "%.2f%%".format(result.confidence * 100)
                else -> "" to ""
            }
            detected_item_1.text = item
            detected_item_value_1.text = value
            inference_time_value.text = getString(R.string.inference_time_placeholder, result.processTimeMs)
        }
    }

    private fun clearUi() {
        detected_item_1.text = ""
        detected_item_value_1.text = ""
        inference_time_value.text = ""
        percentMeter.progress = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraProcessor.isInitialized) cameraProcessor.close()
        backgroundExecutor.shutdown()
    }

    companion object {
        const val TAG = "KotlinDL demo app"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    internal class ModelItemSelectedListener(private val activity: MainActivity) : OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (activity::cameraProcessor.isInitialized) activity.cameraProcessor.imageAnalyzer.setPipeline(position)
        }

        override fun onNothingSelected(p0: AdapterView<*>?) {
            if (activity::cameraProcessor.isInitialized) activity.cameraProcessor.imageAnalyzer.clear()
        }
    }
}

private class CameraProcessor(
    val imageAnalyzer: ImageAnalyzer,
    private val cameraProvider: ProcessCameraProvider,
    surfaceProvider: Preview.SurfaceProvider,
    executor: Executor
) {
    private val imagePreview = Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build()
        .also {
            it.setSurfaceProvider(surfaceProvider)
        }
    private val imageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also {
            it.setAnalyzer(executor, imageAnalyzer)
        }

    fun bindCameraUseCases(lifecycleOwner: LifecycleOwner): Boolean {
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imagePreview,
                imageAnalysis
            )
            return true
        } catch (exc: RuntimeException) {
            Log.e(MainActivity.TAG, "Use case binding failed", exc)
        }
        return false
    }

    fun close() {
        cameraProvider.unbindAll()
        imageAnalyzer.close()
    }
}
