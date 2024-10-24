package com.tharunbirla.fetchit

import android.Manifest
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
import android.util.Patterns
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.fetchit.utils.FacebookUrlFetcher
import com.tharunbirla.fetchit.utils.InstagramUrlFetcher
import com.tharunbirla.fetchit.utils.TwitterUrlFetcher
import com.tharunbirla.fetchit.utils.YouTubeUrlFetcher
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
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val requestPermissionCode = 1001
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private val channelId = "download_channel"
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        setupPermissions()
        setupUI()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the activity's intent
        setIntent(intent)
        // Handle the new intent
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSharedText(intent)
                }
            }
            Intent.ACTION_VIEW -> {
                handleViewAction(intent)
            }
        }
    }

    private fun handleViewAction(intent: Intent) {
        intent.data?.toString()?.let { urlString ->
            if (isValidUrl(urlString)) {
                findViewById<TextInputEditText>(R.id.urlInput).setText(urlString)
                findViewById<MaterialButton>(R.id.downloadButton).performClick()
            } else {
                showToast("Invalid URL format")
            }
        }
    }

    private fun handleSharedText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            // Try to extract URL from shared text
            val urls = extractUrls(sharedText)
            if (urls.isNotEmpty()) {
                val url = urls.first()
                if (isValidUrl(url)) {
                    findViewById<TextInputEditText>(R.id.urlInput).setText(url)
                    // Automatically trigger the download button
                    findViewById<MaterialButton>(R.id.downloadButton).performClick()
                } else {
                    showToast("Invalid URL format")
                }
            } else {
                showToast("No valid URL found")
            }
        }
    }

    private fun isValidUrl(urlString: String): Boolean {
        return try {
            // First check using Android's URLUtil
            if (!URLUtil.isValidUrl(urlString)) {
                return false
            }

            // Additional validation by attempting to create a URL object
            val url = URL(urlString)

            // Check if the URL has a protocol and host
            url.protocol.isNotEmpty() && url.host.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun extractUrls(text: String): List<String> {
        // Simple URL extraction using regex
        val urlRegex = Patterns.WEB_URL.pattern().toRegex()
        return urlRegex.findAll(text)
            .map { it.value }
            .filter { isValidUrl(it) }
            .toList()
    }

    private fun setupPermissions() {
        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                showToast("Permissions granted")
            } else {
                showToast("Some permissions were denied")
            }
        }

        if (!arePermissionsGranted()) {
            showPermissionRequestDialog()
        }
    }

    private fun setupUI() {
        val backgroundColor = ContextCompat.getColor(this, R.color.background)
        window.statusBarColor = backgroundColor

        val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
        val downloadButton = this.findViewById<MaterialButton>(R.id.downloadButton)
        val copyActionButton = findViewById<FloatingActionButton>(R.id.copyButton)

        saveLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> startDownload(uri) }
            }
        }

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                openFilePicker()
            } else {
                showToast("Please enter a valid URL")
            }
        }

        copyActionButton.setOnClickListener {
            pasteClipboardToInput()
        }
    }

    private fun arePermissionsGranted(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                checkPermissions(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                checkPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
            else -> {
                checkPermissions(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
    }

    private fun checkPermissions(vararg permissions: String): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionRequestDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app needs permissions to access media files and show notifications.")
            .setPositiveButton("Grant") { _, _ -> requestPermissions() }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> {
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
        requestPermissionsLauncher.launch(permissions)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (Intent.ACTION_SEND == intent?.action && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                findViewById<TextInputEditText>(R.id.urlInput).setText(sharedText)
                openFilePicker()
            }
        }
    }

    private fun pasteClipboardToInput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.let { pastedText ->
            findViewById<TextInputEditText>(R.id.urlInput).setText(pastedText)
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

    private fun startDownload(uri: Uri) {
        val url = findViewById<TextInputEditText>(R.id.urlInput).text.toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val videoUrl = when {
                    url.contains("youtube.com") || url.contains("youtu.be") -> YouTubeUrlFetcher.fetchYouTubeVideoUrl(url)
                    url.contains("twitter.com") -> TwitterUrlFetcher.fetchTwitterVideoUrl(url)
                    url.contains("instagram.com") -> InstagramUrlFetcher.fetchInstagramVideoUrl(url)
                    url.contains("facebook.com") -> FacebookUrlFetcher.fetchFacebookVideoUrl(url)
                    else -> YouTubeUrlFetcher.fetchYouTubeVideoUrl(url)
                }

                videoUrl?.let { downloadUrl ->
                    val success = downloadFile(downloadUrl, uri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            showNotification("Download Complete", "Video has been saved successfully")
                            findViewById<TextInputEditText>(R.id.urlInput).text?.clear()
                        } else {
                            showNotification("Download Failed", "Unable to download the video")
                        }
                    }
                } ?: withContext(Dispatchers.Main) {
                    showNotification("Error", "Could not retrieve video URL")
                }
            } catch (e: Exception) {
                Log.e("Download", "Error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showNotification("Error", "Download failed: ${e.message}")
                }
            }
        }
    }

    private fun downloadFile(videoUrl: String, uri: Uri): Boolean {
        return try {
            val request = Request.Builder().url(videoUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("Download", "Error: ${e.message}", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Download Notifications"
            val descriptionText = "Notifications for download status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(this).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notify(System.currentTimeMillis().toInt(), builder.build())
                }
            } else {
                notify(System.currentTimeMillis().toInt(), builder.build())
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}