package com.carlink.app.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarLink x86 debug build: copies the app's own logcat (every CarLink process, CarPlay and Android
 * Auto engines included) into rotating files the driver can copy off the head unit with a file
 * manager: Android/data/<package>/files/carlink-logs/ on the internal storage.
 */
object FileLogcat {
    private const val TAG = "CarLinkFileLog"
    private const val KEEP_FILES = 8
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        Thread({ run(app) }, "carlink-file-logcat").apply { isDaemon = true }.start()
    }

    /** Starts the next file now, in the shared folder once storage access was just granted. */
    fun rotate() {
        // The new file repeats what logcat still holds, so the shared folder has the whole start.
        first = true
        current?.destroy()
    }

    @Volatile private var current: Process? = null
    @Volatile private var first = true

    @Suppress("DEPRECATION")
    private fun sharedDirectory(): File = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CarLink-Logs")

    private fun privateDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "carlink-logs")

    /** Every folder log files may be in, for the debug server. */
    fun directories(context: Context): List<File> = listOf(sharedDirectory(), privateDirectory(context))

    /**
     * Download/CarLink-Logs when CarLink may write there (every file manager shows it), else the
     * app's own folder Android/data/<package>/files/carlink-logs.
     */
    private fun directory(context: Context): File {
        val shared = sharedDirectory()
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted && (shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
        return privateDirectory(context)
    }

    private fun run(context: Context) {
        while (true) {
            runCatching {
                val dir = directory(context)
                dir.mkdirs()
                dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "carlink-$stamp.log")
                val header = "CarLink log ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} " +
                    "abis=${Build.SUPPORTED_ABIS.joinToString()}\n"
                // The first file starts with what logcat still holds from before CarLink started; later
                // files continue from now instead of repeating it.
                val args = listOf("logcat", "-v", "threadtime", "-b", "main,system,crash") +
                    if (first) emptyList() else listOf("-T", "1")
                first = false
                val process = ProcessBuilder(args).redirectErrorStream(true).start()
                current = process
                Log.i(TAG, "writing ${file.absolutePath}")
                file.outputStream().buffered().use { out ->
                    out.write(header.toByteArray())
                    val input = process.inputStream.buffered()
                    val buffer = ByteArray(8192)
                    var written = 0L
                    var sinceFlush = 0L
                    while (written < MAX_FILE_BYTES) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        sinceFlush += n
                        // Flush often: the head unit is switched off with the ignition, without warning.
                        if (sinceFlush > 4096 || input.available() == 0) { out.flush(); sinceFlush = 0 }
                    }
                }
                process.destroy()
                Thread.sleep(1_000)
            }.onFailure {
                Log.w(TAG, "file log stopped, restarting", it)
                Thread.sleep(5_000)
            }
        }
    }
}
