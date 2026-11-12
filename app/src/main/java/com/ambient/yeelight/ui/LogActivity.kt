package com.ambient.yeelight.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.ambient.yeelight.R
import com.ambient.yeelight.databinding.ActivityLogBinding
import com.ambient.yeelight.logger
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The end of the app's log, newest at the bottom. Saves the log files as a zip to Downloads or
 * sends it with another app; clears the log.
 */
class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    // Android 9 only: a later Android saves to Downloads with no permission
    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveLog()
        }
        else {
            showToast(getString(R.string.log_save_no_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener {
            finish()
        }

        binding.saveButton.setOnClickListener {
            if (needsStoragePermission()) {
                requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@setOnClickListener
            }

            saveLog()
        }

        binding.sendButton.setOnClickListener {
            sendLog()
        }

        binding.clearButton.setOnClickListener {
            confirmClear()
        }

        showLog()
    }

    /** Reads the end of the log in the background and shows it scrolled to the bottom. */
    private fun showLog() {
        lifecycleScope.launch {
            val text = readLastLines()

            binding.logText.text = text.ifEmpty { getString(R.string.log_empty) }
            binding.logScroll.post {
                binding.logScroll.scrollTo(0, binding.logText.height)
            }
        }
    }

    /** The last MAX_LINES lines of the current log file; empty if there is none. */
    private suspend fun readLastLines(): String {
        val file = logger.files.lastOrNull() ?: return ""

        return withContext(Dispatchers.IO) {
            try {
                file.readLines().takeLast(MAX_LINES).joinToString("\n")
            }
            catch (e: IOException) {
                logger.w(TAG, "Cannot read the log -> $e")
                ""
            }
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false

        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE

        return ContextCompat.checkSelfPermission(this, permission) !=
            PackageManager.PERMISSION_GRANTED
    }

    /** Saves the log files as one zip to Downloads, in the background, and tells how it went. */
    private fun saveLog() {
        lifecycleScope.launch {
            val name = createArchiveName()
            val saved = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        saveToMediaStore(name)
                    }
                    else {
                        saveToDownloads(name)
                    }
                    true
                }
                catch (e: IOException) {
                    logger.w(TAG, "Cannot save the log -> $e")
                    false
                }
            }

            if (saved) {
                logger.i(TAG, "Log saved to Downloads/$name")
                showToast(getString(R.string.log_saved, name))
            }
            else {
                showToast(getString(R.string.log_not_saved))
            }
        }
    }

    /** Android 10 and later: Downloads through MediaStore, no permission. Throws IOException. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(name: String) {
        val values = ContentValues()

        values.put(MediaStore.Downloads.DISPLAY_NAME, name)
        values.put(MediaStore.Downloads.MIME_TYPE, ZIP_TYPE)

        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore did not make $name")
        val output = contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open $uri")

        writeArchive(output)
    }

    /** Android 9: a file in Downloads, with the storage permission; throws IOException. */
    @Suppress("DEPRECATION") // The way to reach Downloads on Android 9
    private fun saveToDownloads(name: String) {
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        folder.mkdirs()
        writeArchive(File(folder, name).outputStream())
    }

    /** Makes the zip in the background and offers to send it: mail, a messenger, a cloud drive. */
    private fun sendLog() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                try {
                    writeSharedArchive()
                }
                catch (e: IOException) {
                    logger.w(TAG, "Cannot make the log zip -> $e")
                    null
                }
            }

            if (file == null) {
                showToast(getString(R.string.log_not_sent))
                return@launch
            }

            val uri = FileProvider.getUriForFile(this@LogActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(ZIP_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_subject))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            try {
                startActivity(Intent.createChooser(send, getString(R.string.send_log)))
            }
            catch (e: ActivityNotFoundException) {
                logger.w(TAG, "No app to send the log -> $e")
                showToast(getString(R.string.no_app_to_send))
            }
        }
    }

    /**
     * Writes the zip into the cache folder FileProvider shares, in place of an earlier one, and
     * returns it; throws IOException.
     */
    private fun writeSharedArchive(): File {
        val folder = File(cacheDir, SHARED_FOLDER)
        val file = File(folder, createArchiveName())

        folder.deleteRecursively()
        folder.mkdirs()
        writeArchive(file.outputStream())
        return file
    }

    private fun createArchiveName(): String =
        "ambient_light_logs_${ARCHIVE_TIME.format(LocalDateTime.now())}.zip"

    /** Writes the log files into output as a zip and closes it; throws IOException. */
    private fun writeArchive(output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (file in logger.files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_log_title)
            .setMessage(R.string.clear_log_message)
            .setPositiveButton(R.string.clear_log) { _, _ -> clearLog() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearLog() {
        logger.clear()
        logger.i(TAG, "Log cleared") // Starts the new file, with the system info at its top
        showLog()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "LogActivity"
        const val MAX_LINES = 500 // A 2 MB file is too long to show in full
        const val ZIP_TYPE = "application/zip"
        const val SHARED_FOLDER = "shared" // In the cache; as in res/xml/shared_files.xml
        val ARCHIVE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
