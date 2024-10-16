package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

object InstagramUrlFetcher {
    private val client = OkHttpClient()

    fun fetchInstagramVideoUrl(videoUrl: String): String? {
        val postId = extractPostIdFromUrl(videoUrl)
        val imginnUrl = "https://imginn.com/p/$postId/"
        return try {
            val request = Request.Builder().url(imginnUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val document = Jsoup.parse(response.body?.string() ?: return@use null)
                    document.select(".downloads a[download]").attr("href").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("Instagram", "Error: ${e.message}", e)
            null
        }
    }

    private fun extractPostIdFromUrl(videoUrl: String): String {
        val regex = Regex("instagram\\.com/(?:p|reel|tv)/([^/?]+)")
        return regex.find(videoUrl)?.groups?.get(1)?.value ?: ""
    }
}