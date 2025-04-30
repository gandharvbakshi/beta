package com.example.beta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import java.net.SocketTimeoutException

/*private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()*/

fun provideOkHttpClient(): OkHttpClient {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf(trustManager), java.security.SecureRandom())
    }

    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier(object : HostnameVerifier {
            override fun verify(hostname: String, session: SSLSession): Boolean {
                return true // Disable hostname verification for development
            }
        })
        .connectTimeout(120, TimeUnit.SECONDS)  // Increased from 10 to 30 seconds
        .writeTimeout(120, TimeUnit.SECONDS)    // Maintain or adapt based on needs
        .readTimeout(120, TimeUnit.SECONDS)     // Increase to at least 60 seconds for processing
        .retryOnConnectionFailure(true)         // Enable retry on connection failure
        .build()
}

private val client = provideOkHttpClient() // Use the secure client configuration

fun uploadScreenshotAndProcess(context: Context, bitmap: Bitmap, filename: String) {
    val attemptUpload =
        {
            // Create a temporary file for the bitmap
            val tempFile = File(context.cacheDir, filename)
            FileOutputStream(tempFile).use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            }

            val fileBody = tempFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .build()

            val request = Request.Builder()
                .url("https://10.0.2.2:8000/process-image/") // Use your Docker container URL
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    Log.e("UploadFailure", "Failed to upload image: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        Log.e("UploadResponse", "Unexpected response: $response")
                        return
                    }
                    val startTime = System.currentTimeMillis()  // Start time to calculate round-trip time

                    response.body?.let { responseBody ->
                        val inputStream = responseBody.byteStream()
                        val processedBitmap = BitmapFactory.decodeStream(inputStream)

                        // Check if processedBitmap is null
                        if (processedBitmap == null) {
                            Log.e(
                                "UploadResponse",
                                "Processed bitmap is null. Please check the backend response."
                            )
                            return // Exit early if we can't process the image
                        }

                        val processedFile = File(
                            context.getExternalFilesDir(null),
                            "${filename}_with_bounding_box.jpg"
                        )

                        // Save processed image
                        FileOutputStream(processedFile).use { outStream ->
                            processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
                        }
                        // Log the file path and print confirmation
                        val endTime = System.currentTimeMillis()  // End time to calculate round-trip time
                        val totalTime = endTime - startTime

                        Log.d("ImageProcessing", "Processed image with Bounding Box saved successfully: ${processedFile.absolutePath}")
                        Log.d("ImageTiming", "Round-trip time for image processing: ${totalTime}ms")
                    }
                }
            })
        }

    val maxAttempts = 3
    for (attempt in 1..maxAttempts) {
        try {
            attemptUpload() // Call the uploading method
            break // Break if successful
        } catch (e: SocketTimeoutException) {
            if (attempt == maxAttempts) {
                Log.e("UploadFailure", "Failed after multiple attempts: ${e.message}")
            } else {
                Log.w("UploadRetry", "Attempt $attempt failed, retrying...")
            }
        }
    }

}