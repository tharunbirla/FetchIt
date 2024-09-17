package com.tharunbirla.fetchit

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val requestPermissionCode = 1001
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private val channelId = "download_channel"

    private lateinit var saveLocationLauncher: ActivityResultLauncher<Intent>

    @RequiresApi(Build.VERSION_CODES.M)
    @SuppressLint("WrongViewCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        handleIncomingShareIntent(intent)

        createNotificationChannel()
        requestNotificationPermission()

        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                showNotification("Permissions Granted", "You have granted the required permissions.")
            } else {
                showNotification("Permissions Denied", "Some permissions were denied.")
            }
        }
        // Check and request permissions if not granted
        if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arePermissionsGranted()
            } else {
                TODO("VERSION.SDK_INT < TIRAMISU")
            }
        ) {
            // Permissions are already granted, no need to show dialog
            return
        }
        @RequiresApi(Build.VERSION_CODES.M)
        fun checkAndRequestPermissions() {
            if (arePermissionsGranted()) {
                return
            }
            showPermissionRequestDialog()
        }

        val backgroundColor = ContextCompat.getColor(this, R.color.background_color)
        window.statusBarColor = backgroundColor

        val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
        val downloadButton = findViewById<MaterialButton>(R.id.downloadButton)
        val copyActionButton = findViewById<FloatingActionButton>(R.id.copyButton)

        saveLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let { startDownload(it) }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), requestPermissionCode)
        }

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                openFilePicker()
            } else {
                Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
            }
        }

        copyActionButton.setOnClickListener {
            pasteClipboardToInput()
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (Intent.ACTION_SEND == intent?.action && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrEmpty()) {
                // Automatically handle the shared URL
                findViewById<TextInputEditText>(R.id.urlInput).setText(sharedText)
                openFilePicker()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun arePermissionsGranted(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }


    @RequiresApi(Build.VERSION_CODES.M)
    private fun showPermissionRequestDialog() {
        AlertDialog.Builder(this)
            .setTitle("Request Permissions")
            .setMessage("This app needs permissions to access media files and clipboard. Please grant the permissions.")
            .setPositiveButton("OK") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showNotification("Permissions Denied", "Permissions were not granted.")
            }
            .create()
            .show()
    }


    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        requestPermissionsLauncher.launch(permissions)
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    requestPermissionCode
                )
            }
        }
    }

    private fun pasteClipboardToInput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let { pastedText ->
                val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
                urlInput.setText(pastedText)
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, generateFileName())
        }
        saveLocationLauncher.launch(intent)
    }

    private fun generateFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "video_${dateFormat.format(System.currentTimeMillis())}.mp4"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permission granted", Toast.LENGTH_SHORT).show()
            } else {
//                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startDownload(uri: Uri) {
        val url = findViewById<TextInputEditText>(R.id.urlInput).text.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoUrl = when {
                    url.contains("youtube.com") || url.contains("youtu.be") -> fetchYouTubeVideoUrl(url)
                    url.contains("twitter.com") -> fetchTwitterVideoUrl(url)
                    url.contains("instagram.com") -> fetchInstagramVideoUrl(url)
                    url.contains("facebook.com") -> fetchFacebookVideoUrl(url)
                    else -> fetchYouTubeVideoUrl(url)
                }

                if (videoUrl != null) {
                    val success = downloadFile(videoUrl, uri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            showNotification("Download successful", "File saved successfully.")
                            findViewById<TextInputEditText>(R.id.urlInput).text?.clear()
                        } else {
                            showNotification("Download failed", "Failed to download file.")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showNotification("Download failed", "Failed to get video URL.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showNotification("Download failed", "Error: ${e.message}")
                }
            }
        }
    }

    private fun fetchYouTubeVideoUrl(videoUrl: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://api.cobalt.tools/api/json")
                .post("{\"url\":\"$videoUrl\"}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: return@use null
                    val json = JSONObject(responseData)
                    json.optString("url")
                } else {
                    Log.e("FetchYouTubeVideoUrl", "Error: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("FetchYouTubeVideoUrl", "Exception: ${e.message}", e)
            null
        }
    }

    private fun fetchTwitterVideoUrl(tweetUrl: String): String? {
        val tweetId = extractTweetIdFromUrl(tweetUrl)
        val twitsaveUrl = "https://twitsave.com/info?url=$tweetId"
        return try {
            val request = Request.Builder().url(twitsaveUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: return@use null
                    val document: Document = Jsoup.parse(html)
                    val videoElements = document.select("video[src~=(?i)\\.mp4]")
                    videoElements.firstOrNull()?.attr("src")
                } else {
                    Log.e("FetchTwitterVideoUrl", "Error: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("FetchTwitterVideoUrl", "Exception: ${e.message}", e)
            null
        }
    }

    private fun extractTweetIdFromUrl(url: String): String {
        val regex = "status/(\\d+)".toRegex()
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }

    private fun fetchInstagramVideoUrl(videoUrl: String): String? {
        val postId = extractPostIdFromUrl(videoUrl)
        val imginnUrl = "https://imginn.com/p/$postId/"
        return try {
            val request = Request.Builder().url(imginnUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: return@use null
                    val document: Document = Jsoup.parse(responseData)
                    document.select(".downloads a[download]").attr("href").takeIf { it.isNotEmpty() }
                } else {
                    Log.e("FetchInstagramVideoUrl", "Error: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("FetchInstagramVideoUrl", "Exception: ${e.message}", e)
            null
        }
    }

    private fun extractPostIdFromUrl(videoUrl: String): String {
        val regex = Regex("instagram\\.com/(?:p|reel|tv)/([^/?]+)")
        return regex.find(videoUrl)?.groups?.get(1)?.value.orEmpty()
    }

    private fun fetchFacebookVideoUrl(videoUrl: String): String? {
        val headers = mapOf(
            "sec-fetch-user" to "?1",
            "sec-ch-ua-mobile" to "?0",
            "sec-fetch-site" to "none",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "cors"
        ).toHeaders()

        return try {
            val request = Request.Builder()
                .url(videoUrl)
                .headers(headers)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseData = response.body?.string() ?: return@use null
                    val document: Document = Jsoup.parse(responseData)
                    document.select("meta[property=og:video:url]").attr("content").takeIf { it.isNotEmpty() }
                } else {
                    Log.e("FetchFacebookVideoUrl", "Error: ${response.code} ${response.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e("FetchFacebookVideoUrl", "Exception: ${e.message}", e)
            null
        }
    }

    private fun downloadFile(videoUrl: String, uri: Uri): Boolean {
        return try {
            val request = Request.Builder().url(videoUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { inputStream ->
                        contentResolver.openOutputStream(uri)?.use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    true
                } else {
                    Log.e("DownloadFile", "Error: ${response.code} ${response.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("DownloadFile", "Exception: ${e.message}", e)
            false
        }
    }

    private fun showNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }


        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(1, builder.build())
    }


    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "Download Notifications"
            val descriptionText = "Notifications for download status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        fun requestPermissions(mainActivity: MainActivity) {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES
                )
            } else {
                TODO("VERSION.SDK_INT < TIRAMISU")
            }
            mainActivity.requestPermissionsLauncher.launch(permissions)
        }
    }
}
