package com.daniel.ocr.camera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.WindowMetricsCalculator
import com.daniel.ocr.R
import com.daniel.ocr.databinding.ActivityCameraBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

class CameraXActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var binding: ActivityCameraBinding

    private var displayId: Int = -1
    private var lensFacing: Int? = CameraSelector.LENS_FACING_BACK
    private var isFlash: Int = ImageCapture.FLASH_MODE_OFF
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var gravSensorVals: FloatArray = FloatArray(10)
    private lateinit var sensorManager: SensorManager
    private var tempRotation = 0
    private val alpha = 0.15f

    private lateinit var cameraExecutor: ExecutorService


    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val isGranted = it.entries.all {
                it.value == true
            }

            if (isGranted) {
                runCamera()
            } else {
                it.entries.forEach {
                    if (!it.value) {
                        when (it.key) {
                            Manifest.permission.CAMERA -> {
                                Toast.makeText(this, "Camera not allowed", Toast.LENGTH_SHORT).show()
                                finish()
                            }

                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE -> {
                                Toast.makeText(this, "Storage not allowed", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                    }
                }
            }
        }

    private var requestPermissionStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val isGranted = it.entries.all {
                it.value == true
            }
            if (isGranted) {
                captureImage()
            } else {
                finish()
                Toast.makeText(this,"Storage not allowed", Toast.LENGTH_SHORT).show()
            }
        }

    private fun permissionCameraRequest() {
        var mainPermissions = arrayOf(
            Manifest.permission.CAMERA,
        )
        permissionRequest.launch(mainPermissions)
    }

    private fun runCamera() {
        lifecycleScope.launch {
            setUpCamera()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)


        fullScreen()

        cameraExecutor = Executors.newSingleThreadExecutor()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        binding.viewFinder.post {
            displayId = binding.viewFinder.display.displayId
        }

        updateCameraUi()

        permissionCameraRequest()
    }

    private fun fullScreen() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

            view.onApplyWindowInsets(windowInsets)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }


    private fun updateCameraUi() {
        binding.imageCaptureButton.setOnClickListener {
//            var requestPermission = arrayOf(
//                Manifest.permission.WRITE_EXTERNAL_STORAGE,
//            )
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//                requestPermission += MANAGE_EXTERNAL_STORAGE
//            }
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                requestPermission +=  Manifest.permission.READ_MEDIA_IMAGES
//            }else requestPermission += Manifest.permission.READ_EXTERNAL_STORAGE
//            requestPermissionStorage.launch(requestPermission)
            captureImage()
        }

        binding.cameraSwitchButton.let {
            it.isEnabled = false

            it.setOnClickListener {
                if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    binding.btnFlash.visibility = View.VISIBLE
                    lensFacing = CameraSelector.LENS_FACING_BACK
                } else {
                    binding.btnFlash.visibility = View.GONE
                    lensFacing = CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        binding.btnFlash.setOnClickListener {
            if (isFlash == ImageCapture.FLASH_MODE_ON) {
                isFlash = ImageCapture.FLASH_MODE_OFF
                binding.btnFlash.setImageDrawable(getDrawable(R.drawable.ic_flash_off))
            } else {
                isFlash = ImageCapture.FLASH_MODE_ON
                binding.btnFlash.setImageDrawable(getDrawable(R.drawable.ic_flash_on))
            }
            imageCapture!!.flashMode = isFlash
        }
    }

    private fun captureImage() {
        // Get a stable reference of the modifiable image capture use case

        val targetFile = getTargetFile()

        targetFile.listFiles()?.map {
            it.delete()
        }.also {
            imageCapture?.let { imageCapture ->

                // Create time stamped name and MediaStore entry.
                val name = UUID.randomUUID().toString()

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            File(Environment.DIRECTORY_PICTURES, getAppDir()).path
                        )
                    } else {
                        put(MediaStore.Images.Media.DATA, File(targetFile, name).path)
                    }
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions
                    .Builder(
                        contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                    .build()


                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            lifecycleScope.launch {
                                Toast.makeText(
                                    this@CameraXActivity,
                                    exc.message,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            lifecycle.coroutineScope.launch {
                                val intent = Intent(
                                    this@CameraXActivity,
                                    PreviewActivity::class.java
                                ).apply {
                                    putExtra(TARGET_FILE, targetFile)
                                }
                                startForResultPreviewImage.launch(intent)
                            }


                        }
                    })

                // We can only change the foreground Drawable using API level 23+ API

                // Display flash animation to indicate that photo was captured
                binding.root.postDelayed({
                    binding.root.foreground = ColorDrawable(Color.WHITE)
                    binding.root.postDelayed(
                        { binding.root.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    private fun getTargetFile(): File {
//            val storagePath = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val storagePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val targetFile = File(storagePath, getAppDir())
        if (!targetFile.isDirectory) targetFile.mkdirs()
        return targetFile
    }

    private fun getAppDir():String{
        return  "/${getString(R.string.app_name)}"
    }

    val startForResultPreviewImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data
                if (intent != null) {
                    val file =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(
                            IMAGE_CAMERA, File::class.java
                        )
                        else intent.getSerializableExtra(IMAGE_CAMERA) as File

                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(IMAGE_CAMERA, file)
                    })
                    finish()
                }
            }
        }

    private fun updateCameraSwitchButton() {
        try {
            binding.cameraSwitchButton.isEnabled =
                hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            binding.cameraSwitchButton.isEnabled = false
        }
    }

    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bindCameraUseCases()
        updateCameraSwitchButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(this).await()

        lensFacing = when {
            hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> null
        }

        if (lensFacing != null) {
            updateCameraSwitchButton()

            bindCameraUseCases()
        } else {
            Toast.makeText(
                this@CameraXActivity,
                "Back and front camera are unavailable",
                Toast.LENGTH_SHORT
            ).show()
        }


    }

    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        // CameraProvider
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing!!).build()

        // Preview
        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
            .setFlashMode(isFlash)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // ImageAnalysis
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .build()

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        if (camera != null) {
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(this)
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9

    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            gravSensorVals = lowPass(event.values!!.clone(), gravSensorVals)
        }

        val x = gravSensorVals[0]
        val y = gravSensorVals[1]

        val degreeRotation = atan2(x, y)

        val rotation = Math.toDegrees(degreeRotation.toDouble())
        val calculateRotation = calculateRotate(rotation.toInt())

        if (tempRotation != calculateRotation) {
            tempRotation = calculateRotation

            imageCapture?.targetRotation = toTargetRotation(tempRotation)
            imageAnalyzer?.targetRotation = toTargetRotation(tempRotation)
        }

    }

    private fun toTargetRotation(tempRotation: Int): Int {
        if (tempRotation == 0) return 0
        else if (tempRotation == 90) return 1
        else if (tempRotation == 180) return 2
        else if (tempRotation == 270) return 3
        else return 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

    }

    private fun lowPass(input: FloatArray, output: FloatArray?): FloatArray {
        if (output == null) return input

        for (i in input.indices) {
            output[i] = output[i] + alpha * (input[i] - output[i])
        }
        return output
    }

    private fun calculateRotate(rotation: Int): Int {
        val fixRotate: Int
        val threshold = 45

        if (rotation < 0) fixRotate = rotation + 360
        else fixRotate = rotation


        if (fixRotate < (0 + threshold)) return 0
        else if (fixRotate > (90 - threshold) && fixRotate < (90 + threshold)) return 90
        else if (fixRotate > (180 - threshold) && fixRotate < (180 + threshold)) return 180
        else if (fixRotate > (270 - threshold) && fixRotate < (270 + threshold)) return 270
        else if (fixRotate > (360 - threshold)) return 0
        else return 0
    }

    companion object {
        private const val TAG = "CameraX"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val ANIMATION_FAST_MILLIS = 50L
        private const val ANIMATION_SLOW_MILLIS = 100L

        const val TARGET_FILE = "targetFile"
        const val IMAGE_CAMERA = "imageCamera"
    }
}