package com.example.beta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextRecognitionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_recognition)

        // Load a sample image from resources for demo purposes
        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.swiggy_screenshot)
        if (bitmap == null) {
            Log.e("TextRecognitionActivity", "Bitmap could not be loaded!")
            return
        }

        val image = InputImage.fromBitmap(bitmap, 0)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Log or display recognized text
                Log.d("TextRecognition", "Recognized text: ${visionText.text}")
            }
            .addOnFailureListener { e ->
                Log.e("TextRecognition", "Text recognition failed", e)
            }
    }
}
