package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object FacebookUrlFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchFacebookVideoUrl(videoUrl: String): String? {
        val headers = mapOf(
            "sec-fetch-user" to "?1",
            "sec-ch-ua-mobile" to "?0",
            "sec-fetch-site" to "none",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "cache-control" to "max-age=0",
            "authority" to "www.facebook.com",
            "upgrade-insecure-requests" to "1",
            "accept-language" to "en-GB,en;q=0.9,tr-TR;q=0.8,tr;q=0.7,en-US;q=0.6",
            "sec-ch-ua" to "\"Google Chrome\";v=\"89\", \"Chromium\";v=\"89\", \";Not A Brand\";v=\"99\"",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.114 Safari/537.36",
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        ).toHeaders()

        return try {
            val request = Request.Builder()
                .url(videoUrl)
                .headers(headers)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        Log.e("Facebook", "Empty response body")
                        return null
                    }
                    return parseVideoDetailsFromHtml(body)
                } else {
                    Log.e("Facebook", "Error: HTTP ${response.code}, Message: ${response.message}")
                    return null
                }
            }
        } catch (e: Exception) {
            Log.e("Facebook", "Error: ${e.message}", e)
            null
        }
    }

    private fun parseVideoDetailsFromHtml(html: String): String? {
        val title = extractTitle(html)
        val sdLink = getSDLink(html)
        val hdLink = getHDLink(html)

        Log.d("Facebook", "Title: $title, SD Link: $sdLink, HD Link: $hdLink")

        return hdLink ?: sdLink // Return HD link if available, otherwise SD link
    }

    private fun extractTitle(html: String): String {
        val titleRegex = """<title>(.*?)</title>""".toRegex()
        return titleRegex.find(html)?.groups?.get(1)?.value?.trim() ?: "Unknown Title"
    }

    private fun getSDLink(html: String): String? {
        val regex = """"browser_native_sd_url":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun getHDLink(html: String): String? {
        val regex = """"browser_native_hd_url":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun cleanStr(str: String): String {
        return "{\"text\": \"$str\"}".let { json ->
            val jsonObject = JSONObject(json)
            jsonObject.getString("text")
        }
    }
}
