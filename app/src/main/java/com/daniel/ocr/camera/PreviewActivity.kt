package com.daniel.ocr.camera

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.daniel.ocr.camera.CameraXActivity.Companion.IMAGE_CAMERA
import com.daniel.ocr.camera.CameraXActivity.Companion.TARGET_FILE
import com.daniel.ocr.databinding.ActivityPreviewBinding
import java.io.File

class PreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fullScreen()

        val targetFile =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(TARGET_FILE, File::class.java)
            else intent.getSerializableExtra(TARGET_FILE) as File


        if (targetFile != null && targetFile.listFiles()!!.isNotEmpty()){
            val fileName = targetFile.listFiles()!!.first()?.name
            Glide.with(this@PreviewActivity).load(File(targetFile, fileName!!)).into(binding.viewImage)

            binding.apply {
                btnDelete.setOnClickListener {
                    deleteAllFiles(targetFile)
                    finish()
                }

                btnOke.setOnClickListener {
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(IMAGE_CAMERA, File(targetFile, fileName))
                    })
                    finish()
                }
            }
        }

    }

    private fun fullScreen() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        window.decorView.setOnApplyWindowInsetsListener { view, windowInsets ->
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())

            view.onApplyWindowInsets(windowInsets)
        }
    }

    private fun deleteAllFiles(targetFile: File){
        if (!targetFile.isDirectory) {
            targetFile.mkdirs()
        }


        if (targetFile.listFiles()!!.isNotEmpty()) {
            targetFile.listFiles()!!.map {
                File(targetFile, it.name).delete()
            }
        }
    }
}