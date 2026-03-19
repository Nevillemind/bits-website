package com.bits.fieldcoach.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for BITS Field Coach AI backend.
 * Connects to bitsfieldcoach.com for voice Q&A, vision analysis, and escalation.
 */
class FieldCoachClient(
    private val baseUrl: String = "https://bitsfieldcoach.com"
) {
    companion object {
        private const val TAG = "FieldCoachClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Send a voice question to Field Coach AI and get text answer back
     */
    suspend fun askQuestion(question: String, techId: String = "glasses_user"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("question", question)
                    put("tech_id", techId)
                }

                val request = Request.Builder()
                    .url("$baseUrl/glasses/ask-text")
                    .post(json.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Server returned ${response.code}"))
                }

                val body = response.body?.string() ?: ""
                val data = JSONObject(body)
                val answer = data.getString("answer")

                Log.d(TAG, "Got answer: ${answer.take(80)}...")
                Result.success(answer)

            } catch (e: Exception) {
                Log.e(TAG, "Error asking question", e)
                Result.failure(e)
            }
        }

    /**
     * Send a photo + question to Field Coach vision AI
     */
    suspend fun analyzePhoto(
        question: String,
        imageBytes: ByteArray,
        techId: String = "glasses_user"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val json = JSONObject().apply {
                put("question", question)
                put("image_base64", imageBase64)
                put("tech_id", techId)
            }

            val request = Request.Builder()
                .url("$baseUrl/glasses/vision-text")
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Server returned ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val data = JSONObject(body)
            val answer = data.getString("answer")

            Log.d(TAG, "Vision answer: ${answer.take(80)}...")
            Result.success(answer)

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing photo", e)
            Result.failure(e)
        }
    }

    /**
     * Escalate to supervisor
     */
    suspend fun escalate(
        techId: String,
        question: String,
        aiResponse: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("tech_id", techId)
                put("question", question)
                put("ai_response", aiResponse)
            }

            val request = Request.Builder()
                .url("$baseUrl/glasses/escalate")
                .post(json.toString().toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Server returned ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val data = JSONObject(body)
            val sessionId = data.getString("session_id")

            Result.success(sessionId)

        } catch (e: Exception) {
            Log.e(TAG, "Error escalating", e)
            Result.failure(e)
        }
    }

    /**
     * Check server health
     */
    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/glasses/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
