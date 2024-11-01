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
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.webkit.URLUtil
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
import com.tharunbirla.fetchit.utils.FacebookUrlFetcher
import com.tharunbirla.fetchit.utils.InstagramUrlFetcher
import com.tharunbirla.fetchit.utils.TwitterUrlFetcher
import com.tharunbirla.fetchit.utils.YouTubeUrlFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val PROGRESS_NOTIFICATION_ID = 100
    private val COMPLETION_NOTIFICATION_ID = 101
    private var isDownloadStarted = false
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private val channelId = "download_channel"
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Intent>

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setupPermissions()
        }
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupPermissions() {
        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                showToast("All permissions granted")
                // Enable download functionality
                findViewById<MaterialButton>(R.id.downloadButton).isEnabled = true
            } else {
                handleDeniedPermissions(permissions)
            }
        }

        // Check permissions on startup
        if (!arePermissionsGranted()) {
            showPermissionRequestDialog()
        } else {
            // Enable download functionality if permissions are already granted
            findViewById<MaterialButton>(R.id.downloadButton).isEnabled = true
        }
    }

    private fun handleDeniedPermissions(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        if (deniedPermissions.isNotEmpty()) {
            val permanentlyDenied = deniedPermissions.any { permission ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    !shouldShowRequestPermissionRationale(permission)
                } else {
                    TODO("VERSION.SDK_INT < M")
                }
            }

            if (permanentlyDenied) {
                showSettingsDialog()
            } else {
                showPermissionExplanationDialog(deniedPermissions.toTypedArray())
            }

            // Disable download functionality
            findViewById<MaterialButton>(R.id.downloadButton).isEnabled = false
        }
    }

    private fun showPermissionExplanationDialog(permissions: Array<String>) {
        val permissionNames = permissions.joinToString("\n") { permission ->
            when (permission) {
                Manifest.permission.POST_NOTIFICATIONS -> "• Notifications"
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "• Storage access"
                else -> "• $permission"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("The following permissions are needed for full functionality:\n\n$permissionNames")
            .setPositiveButton("Try Again") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showToast("Some features may be limited")
            }
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Some permissions are permanently denied. Please enable them in Settings to use all features.")
            .setPositiveButton("Go to Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showToast("Some features may be limited")
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun arePermissionsGranted(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                checkPermissions(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // For Android 10 (Q) and above, we don't need WRITE_EXTERNAL_STORAGE
                checkPermissions(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
            else -> {
                checkPermissions(
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
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                TODO("VERSION.SDK_INT < TIRAMISU")
            }
            else -> {
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
        }
        requestPermissionsLauncher.launch(permissions)
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
        // Reset download started flag at the beginning of each download
        isDownloadStarted = false

        return try {
            val request = Request.Builder().url(videoUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val totalBytes = response.body.contentLength() ?: 0L
                    var downloadedBytes = 0L

                    response.body.byteStream().use { input ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                // Calculate and show progress
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes * 100 / totalBytes).toInt()
                                } else {
                                    0
                                }

                                // Throttle notification updates to prevent excessive system load
                                if (progress % 5 == 0) {
                                    showProgressNotification(progress, totalBytes)
                                }
                            }
                        }
                    }

                    // Remove progress notification and show completion notification
                    cancelProgressNotification()
                    showCompletionNotification("Download Complete", "Video saved successfully", true)
                    true
                } else {
                    // Remove progress notification and show error notification
                    cancelProgressNotification()
                    showCompletionNotification("Download Failed", "Unable to download the video", false)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("Download", "Error: ${e.message}", e)
            // Remove progress notification and show error notification
            cancelProgressNotification()
            showCompletionNotification("Download Error", "Download failed: ${e.message}", false)
            false
        }
    }

    private fun showCompletionNotification(title: String, message: String, isSuccess: Boolean) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setAutoCancel(true)

        if (isSuccess) {
            builder.setSmallIcon(R.drawable.ic_file_download_done_24)
        } else {
            builder.setSmallIcon(R.drawable.ic_error_24)
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this).notify(COMPLETION_NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            Log.e("Notification", "Error showing completion notification", e)
        }
    }

    private fun cancelProgressNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("Notification", "Error canceling progress notification", e)
        }
    }

    private fun showProgressNotification(progress: Int, totalBytes: Long) {
        // Ensure progress is within valid range
        val safeProgress = progress.coerceIn(0, 100)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Downloading Video")
            .setContentText("${formatFileSize(totalBytes)} - ${safeProgress}%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, safeProgress, false)

        // Only play sound and show heads-up notification on first progress update
        if (!isDownloadStarted) {
            builder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            isDownloadStarted = true
        } else {
            // Ensure silent updates for progress
            builder.setSound(null)
                .setSilent(true)
        }

        // Safely check and post notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, builder.build())
                }
            } else {
                NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            Log.e("ProgressNotification", "Error showing progress notification", e)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
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

    private fun showNotification(title: String, message: String, isComplete: Boolean = false) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI) // Play sound for completion/error

        if (isComplete) {
            builder.setOngoing(false)
                .setProgress(0, 0, false)
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Use a different notification ID for completion/error notifications
                NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID + 1, builder.build())
            }
        } catch (e: Exception) {
            Log.e("Notification", "Error showing notification", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}