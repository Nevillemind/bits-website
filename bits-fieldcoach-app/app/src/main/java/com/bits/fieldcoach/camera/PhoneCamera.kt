package com.bits.fieldcoach.camera

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uses the PHONE camera to capture photos for vision AI.
 * This is the immediate workaround until BLE camera access is built.
 * 
 * Field tech taps CAM button → phone camera opens → snap → AI analyzes → speaks result
 */
class PhoneCamera {
    companion object {
        private const val TAG = "PhoneCamera"
        const val REQUEST_CODE = 2001
    }

    private var currentPhotoPath: String? = null
    private var photoCallback: ((ByteArray?) -> Unit)? = null

    /**
     * Create the intent to launch phone camera
     */
    fun createCameraIntent(activity: Activity): Intent? {
        return try {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            
            // Create file to save full-res photo
            val photoFile = createImageFile(activity)
            currentPhotoPath = photoFile.absolutePath

            val photoUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                photoFile
            )
            intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            intent
        } catch (e: Exception) {
            Log.e(TAG, "Error creating camera intent", e)
            // Fallback — capture thumbnail only
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }
    }

    /**
     * Process the camera result and return JPEG bytes
     */
    fun processResult(resultCode: Int, data: Intent?): ByteArray? {
        if (resultCode != Activity.RESULT_OK) return null

        return try {
            // Try full-res photo from file
            currentPhotoPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(path)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    Log.i(TAG, "Photo captured: ${stream.size()} bytes from file")
                    return stream.toByteArray()
                }
            }

            // Fallback — thumbnail from intent
            val bitmap = data?.extras?.get("data") as? Bitmap
            if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                Log.i(TAG, "Photo captured (thumbnail): ${stream.size()} bytes")
                stream.toByteArray()
            } else {
                Log.e(TAG, "No photo data available")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo", e)
            null
        }
    }

    private fun createImageFile(activity: Activity): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("BITS_${timestamp}_", ".jpg", storageDir)
    }
}
