package com.daniel.ocr

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import com.daniel.ocr.camera.CameraXActivity
import com.daniel.ocr.camera.CameraXActivity.Companion.IMAGE_CAMERA
import com.daniel.ocr.camera.PreviewActivity
import com.daniel.ocr.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isCloud = false
    private var isMLKit = false
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var functions: FirebaseFunctions


    private var resultOpenCamera =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val intent = result.data

                if (intent != null) {
                    val file = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(IMAGE_CAMERA, File::class.java)
                    else intent.getSerializableExtra(IMAGE_CAMERA) as File

                    if (file != null) {
                        val storagePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val appName = getString(R.string.app_name)
                        val childTarget = "/${appName}"
                        val targetDir = File(storagePath, childTarget)

                        if (!targetDir.isDirectory) {
                            targetDir.mkdirs()
                        }

                        val file = File(targetDir, file.name)

                        if (isCloud){
                            var bitmap = getCapturedImage(file.toUri())
                            bitmap = scaleBitmapDown(bitmap, 640)

                            val byteArrayOutputStream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
                            val imageBytes = byteArrayOutputStream.toByteArray()
                            val base64encoded = Base64.encodeToString(imageBytes, Base64.NO_WRAP)


                            val request = JsonObject()
                            val image = JsonObject()
                            val feature = JsonObject()
                            val features = JsonArray()
                            val imageContext = JsonObject()
                            val languageHints = JsonArray()

                            image.add("content", JsonPrimitive(base64encoded))
                            request.add("image", image)
                            // Add features to the request
                            feature.add("type", JsonPrimitive("TEXT_DETECTION"))
                            // Alternatively, for DOCUMENT_TEXT_DETECTION:
                            // feature.add("type", JsonPrimitive("DOCUMENT_TEXT_DETECTION"))
                            features.add(feature)
                            languageHints.add("en")
                            imageContext.add("languageHints", languageHints)
                            request.add("imageContext", imageContext)
                            request.add("features", features)

                            annotateImage(request.toString())
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        val intent = Intent(this@MainActivity, ResultOCRActivity::class.java).apply {
                                            putExtra(RESULT_OCR, task.result.asJsonArray.toString())
                                        }
                                        startActivity(intent)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Failed", Toast.LENGTH_LONG).show()
                                    }
                                    file.delete()
                                }
                        }else if (isMLKit){
                            val image = InputImage.fromFilePath(this, file.toUri())
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    file.delete()
                                    val intent = Intent(this@MainActivity, ResultOCRActivity::class.java).apply {
                                        putExtra(RESULT_OCR, visionText.text)
                                    }
                                    startActivity(intent)
                                }
                                .addOnFailureListener { e ->
                                    file.delete()
                                    Toast.makeText(this@MainActivity, e.toString(), Toast.LENGTH_LONG).show()
                                }
                        }

                    }

                }


            }
        }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension
        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension
            resizedWidth =
                (resizedHeight * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight =
                (resizedWidth * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
        } else if (originalHeight == originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = maxDimension
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false)
    }

    private fun getCapturedImage(selectedPhotoUri: Uri): Bitmap {
        return when {
            Build.VERSION.SDK_INT < 28 -> MediaStore.Images.Media.getBitmap(
                this.contentResolver,
                selectedPhotoUri
            )

            else -> {
                val source = ImageDecoder.createSource(this.contentResolver, selectedPhotoUri)
                ImageDecoder.decodeBitmap(source)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            btnCloud.setOnClickListener {
                isCloud = true
                isMLKit = false
                resultOpenCamera.launch(Intent(this@MainActivity, CameraXActivity::class.java))
            }

            btnMlKit.setOnClickListener {
                isCloud = false
                isMLKit = true
                resultOpenCamera.launch(Intent(this@MainActivity, CameraXActivity::class.java))
            }
        }
    }

    private fun annotateImage(requestJson: String): Task<JsonElement> {
        functions = FirebaseFunctions.getInstance()
        return functions
            .getHttpsCallable("annotateImage")
            .call(requestJson)
            .continueWith { task ->
                // This continuation runs on either success or failure, but if the task
                // has failed then result will throw an Exception which will be
                // propagated down.
                val result = task.result?.data
                JsonParser.parseString(Gson().toJson(result))
            }
    }

    companion object {
        const val RESULT_OCR = "result"
    }
}