package com.carlink.app.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.carlink.core.diagnostics.EventLog
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * CarLink x86 debug build: a small read-only web page and API on port [PORT] in the head unit's
 * Wi-Fi, so the connection can be followed from a computer in the same network. It only reads:
 * device and build facts, USB devices, the projection apps' state, CarLink's logs. A notification
 * shows its address.
 */
object DebugServer {
    const val PORT = 8686
    private const val TAG = "CarLinkDebugServer"
    private const val CHANNEL = "carlink_debug_server"
    private const val NOTIFICATION_ID = 8686

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(isDaemon = true, name = "carlink-debug-server") {
            while (true) {
                runCatching {
                    ServerSocket(PORT).use { server ->
                        Log.i(TAG, "listening on ${addresses().joinToString { "http://$it:$PORT" }}")
                        notifyAddress(app)
                        while (true) {
                            val socket = server.accept()
                            thread(isDaemon = true, name = "carlink-debug-request") { serve(app, socket) }
                        }
                    }
                }.onFailure { Log.w(TAG, "server stopped, restarting", it) }
                Thread.sleep(5_000)
            }
        }
        // The Wi-Fi address changes with the network: refresh the notification now and then.
        thread(isDaemon = true, name = "carlink-debug-address") {
            while (true) {
                Thread.sleep(30_000)
                runCatching { notifyAddress(app) }
            }
        }
    }

    private fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun notifyAddress(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarLink debug server", NotificationManager.IMPORTANCE_LOW))
        }
        val text = addresses().joinToString("  ") { "http://$it:$PORT" }.ifEmpty { "no network" }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("CarLink Debug")
            .setContentText(text)
            .setOngoing(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun serve(context: Context, socket: Socket) {
        socket.use {
            runCatching {
                socket.soTimeout = 10_000
                val reader = socket.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (true) { val header = reader.readLine() ?: break; if (header.isEmpty()) break }
                val target = requestLine.split(" ").getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val out = socket.getOutputStream()
                when {
                    path == "/" -> send(out, "text/html", index(context))
                    path == "/info" -> send(out, "text/plain", info(context))
                    path == "/usb" -> send(out, "text/plain", usb(context))
                    path == "/apps" -> send(out, "text/plain", apps(context))
                    path == "/events" -> send(out, "text/plain", events())
                    path == "/logcat" -> streamCommand(out, listOf("logcat", "-d", "-v", "threadtime", "-t",
                        (query["lines"]?.toIntOrNull() ?: 3000).coerceIn(1, 200_000).toString()))
                    path == "/getprop" -> streamCommand(out, listOf("getprop"))
                    path == "/logs" -> send(out, "text/plain", logFiles(context).joinToString("\n") { "${it.name}\t${it.length()}" })
                    path.startsWith("/logs/") -> {
                        val name = path.removePrefix("/logs/")
                        val file = logFiles(context).firstOrNull { it.name == name }
                        if (file == null) send(out, "text/plain", "not found", "404 Not Found")
                        else sendFile(out, file)
                    }
                    else -> send(out, "text/plain", "not found", "404 Not Found")
                }
            }.onFailure { Log.w(TAG, "request failed", it) }
        }
    }

    private fun index(context: Context): String = """
        <html><head><meta name="viewport" content="width=device-width"><title>CarLink Debug</title></head>
        <body style="font-family:sans-serif"><h1>CarLink Debug</h1>
        <p>${context.packageName} ${versionName(context)}</p><ul>
        <li><a href="/info">/info</a> device, build, network</li>
        <li><a href="/usb">/usb</a> USB devices</li>
        <li><a href="/apps">/apps</a> zLink, CarLink and friends</li>
        <li><a href="/events">/events</a> CarLink connection log</li>
        <li><a href="/logcat?lines=3000">/logcat?lines=N</a> CarLink's logcat</li>
        <li><a href="/getprop">/getprop</a> system properties</li>
        <li><a href="/logs">/logs</a> log files (/logs/&lt;name&gt;)</li>
        </ul></body></html>
    """.trimIndent()

    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun info(context: Context): String = buildString {
        appendLine("time ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("package ${context.packageName} ${versionName(context)}")
        appendLine("device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE})")
        appendLine("android ${Build.VERSION.RELEASE} sdk ${Build.VERSION.SDK_INT} ${Build.DISPLAY}")
        appendLine("abis ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("addresses ${addresses().joinToString()}")
        val metrics = context.resources.displayMetrics
        appendLine("display ${metrics.widthPixels}x${metrics.heightPixels} dpi ${metrics.densityDpi}")
        appendLine("features usb.host=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)} " +
            "wifi.direct=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)}")
    }

    private fun usb(context: Context): String = buildString {
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = manager?.deviceList?.values.orEmpty()
        appendLine("${devices.size} USB device(s)")
        for (device in devices) {
            appendLine("${device.deviceName} vendor=0x%04x product=0x%04x class=${device.deviceClass} '${device.manufacturerName} ${device.productName}' configs=${device.configurationCount} permission=${manager?.hasPermission(device)}"
                .format(device.vendorId, device.productId))
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                appendLine("  interface ${itf.id}/${itf.alternateSetting} class=${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol} endpoints=${itf.endpointCount} '${itf.name}'")
            }
        }
        appendLine()
        appendLine("/dev/bus/usb: " + (File("/dev/bus/usb").walkTopDown().filter { it.isFile }.joinToString { it.path }.ifEmpty { "(none readable)" }))
    }

    private fun apps(context: Context): String = buildString {
        val pm = context.packageManager
        for (name in listOf("com.zjinnova.zlink", "net.easyconn", "com.carlink.app", "com.carlink.app.x86", "com.syu.ms", "com.google.android.googlequicksearchbox")) {
            val info = runCatching { pm.getApplicationInfo(name, 0) }.getOrNull()
            if (info == null) { appendLine("$name: not installed"); continue }
            val version = runCatching { pm.getPackageInfo(name, 0).versionName }.getOrNull()
            val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            val persistent = info.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT != 0
            appendLine("$name: version=$version enabled=${info.enabled} system=$system persistent=$persistent state=${pm.getApplicationEnabledSetting(name)}")
        }
        appendLine()
        val activity = context.getSystemService(android.app.ActivityManager::class.java)
        @Suppress("DEPRECATION")
        activity?.runningAppProcesses?.forEach { appendLine("process ${it.processName} pid=${it.pid} importance=${it.importance}") }
    }

    private fun events(): String {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return EventLog.entries.value.joinToString("\n") { "${format.format(Date(it.timeMillis))} ${it.tag}: ${it.message}" }
    }

    private fun logFiles(context: Context): List<File> =
        FileLogcat.directories(context).flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.isFile }.sortedBy { it.name }

    private fun header(out: OutputStream, type: String, status: String = "200 OK", length: Long? = null) {
        val lengthLine = length?.let { "Content-Length: $it\r\n" } ?: ""
        out.write("HTTP/1.0 $status\r\nContent-Type: $type; charset=utf-8\r\n${lengthLine}Connection: close\r\n\r\n".toByteArray())
    }

    private fun send(out: OutputStream, type: String, body: String, status: String = "200 OK") {
        val bytes = body.toByteArray()
        header(out, type, status, bytes.size.toLong())
        out.write(bytes)
        out.flush()
    }

    private fun sendFile(out: OutputStream, file: File) {
        header(out, "text/plain", length = file.length())
        file.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun streamCommand(out: OutputStream, command: List<String>) {
        header(out, "text/plain")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        try {
            process.inputStream.use { it.copyTo(out) }
        } finally {
            process.destroy()
        }
        out.flush()
    }
}
