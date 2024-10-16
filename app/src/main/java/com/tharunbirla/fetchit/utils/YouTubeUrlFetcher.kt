package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object YouTubeUrlFetcher {
    private val client = OkHttpClient()

    fun fetchYouTubeVideoUrl(videoUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.cobalt.tools/api/json")
                .post("{\"url\":\"$videoUrl\"}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: return@use null)
                    json.optString("url").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("YouTube", "Error: ${e.message}", e)
            null
        }
    }
}