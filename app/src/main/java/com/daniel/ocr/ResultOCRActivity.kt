package com.daniel.ocr

import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.daniel.ocr.MainActivity.Companion.RESULT_OCR
import com.daniel.ocr.camera.CameraXActivity
import com.daniel.ocr.databinding.ActivityResultOcrActivityBinding
import java.io.File

class ResultOCRActivity : AppCompatActivity() {

    private lateinit var binding:ActivityResultOcrActivityBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultOcrActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val resultOCR =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(
                RESULT_OCR, String::class.java)
            else intent.getSerializableExtra(RESULT_OCR) as String

        binding.tvText.text = resultOCR
    }
}