package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

object TwitterUrlFetcher {
    private val client = OkHttpClient()

    fun fetchTwitterVideoUrl(tweetUrl: String): String? {
        val tweetId = extractTweetIdFromUrl(tweetUrl)
        val twitsaveUrl = "https://twitsave.com/info?url=$tweetId"
        return try {
            val request = Request.Builder().url(twitsaveUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val document = Jsoup.parse(response.body?.string() ?: return@use null)
                    document.select("video[src~=(?i)\\.mp4]").firstOrNull()?.attr("src")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("Twitter", "Error: ${e.message}", e)
            null
        }
    }

    private fun extractTweetIdFromUrl(url: String): String {
        return "status/(\\d+)".toRegex().find(url)?.groupValues?.get(1) ?: ""
    }
}