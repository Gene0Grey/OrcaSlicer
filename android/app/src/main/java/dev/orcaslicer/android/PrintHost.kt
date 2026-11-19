package dev.orcaslicer.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The desktop's physical printer hosts this app can talk to (PrintHost subclasses). */
enum class HostType(val label: String) { OctoPrint("OctoPrint"), Moonraker("Klipper (Moonraker)"), PrusaLink("PrusaLink"), Duet("Duet (RepRapFirmware)") }

/** A linked printer: how to reach it. Stored per printer preset. */
data class HostConfig(val type: HostType, val url: String, val apiKey: String, val camera: String) {
    val base get() = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    companion object {
        fun load(ctx: Context, printer: String): HostConfig? {
            val p = ctx.getSharedPreferences("orca", 0)
            val url = p.getString("device_url_$printer", null)?.takeIf { it.isNotBlank() } ?: return null
            return HostConfig(runCatching { HostType.valueOf(p.getString("device_type_$printer", "OctoPrint")!!) }.getOrDefault(HostType.OctoPrint),
                url, p.getString("device_key_$printer", "")!!, p.getString("device_cam_$printer", "")!!)
        }

        fun save(ctx: Context, printer: String, c: HostConfig) = ctx.getSharedPreferences("orca", 0).edit()
            .putString("device_url_$printer", c.url).putString("device_type_$printer", c.type.name)
            .putString("device_key_$printer", c.apiKey).putString("device_cam_$printer", c.camera).apply()
    }
}

/** What the printer reports: state, job progress, temperatures. */
data class HostStatus(val state: String, val file: String, val progress: Double, val nozzle: Double, val nozzleTarget: Double,
                      val bed: Double, val bedTarget: Double, val printTime: Double = 0.0)

/** Upload, status and job control over each host's HTTP API (blocking: call off the main thread). */
object PrintHost {
    private fun open(c: HostConfig, path: String, method: String = "GET"): HttpURLConnection =
        (URL(c.base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 5000; readTimeout = 60000
            if (c.apiKey.isNotBlank()) setRequestProperty("X-Api-Key", c.apiKey)
        }

    private fun HttpURLConnection.check(): String {
        val code = responseCode
        val body = (if (code < 400) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) throw RuntimeException("Printer answered $code ${body.take(200)}")
        return body
    }

    private fun get(c: HostConfig, path: String) = JSONObject(open(c, path).check().ifBlank { "{}" })

    private fun post(c: HostConfig, path: String, json: String? = null) = open(c, path, "POST").run {
        if (json != null) { doOutput = true; setRequestProperty("Content-Type", "application/json"); outputStream.use { it.write(json.toByteArray()) } }
        check()
    }

    /** Multipart form upload of [file] as field "file" plus extra fields. */
    private fun multipart(c: HostConfig, path: String, file: File, name: String, fields: Map<String, String>, progress: (Float) -> Unit) {
        val boundary = "----orca" + System.currentTimeMillis()
        open(c, path, "POST").run {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(64 * 1024)
            DataOutputStream(outputStream).use { o ->
                fields.forEach { (k, v) -> o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
                o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                copy(file, o, progress)
                o.writeBytes("\r\n--$boundary--\r\n")
            }
            check()
        }
    }

    private fun copy(file: File, o: java.io.OutputStream, progress: (Float) -> Unit) {
        val total = file.length().coerceAtLeast(1); var sent = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); sent += n; progress(sent.toFloat() / total) }
        }
    }

    /** Sends a G-code file; [print] starts it right away (desktop "Upload and Print"). */
    fun upload(c: HostConfig, file: File, name: String, print: Boolean, progress: (Float) -> Unit) {
        when (c.type) {
            HostType.OctoPrint -> multipart(c, "/api/files/local", file, name, mapOf("select" to "$print", "print" to "$print"), progress)
            HostType.Moonraker -> multipart(c, "/server/files/upload", file, name, mapOf("root" to "gcodes", "print" to "$print"), progress)
            HostType.PrusaLink -> open(c, "/api/v1/files/usb/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}", "PUT").run {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Print-After-Upload", if (print) "?1" else "?0")
                setRequestProperty("Overwrite", "?1")
                setFixedLengthStreamingMode(file.length())
                outputStream.use { copy(file, it, progress) }
                check()
            }
            HostType.Duet -> {
                open(c, "/rr_upload?name=${URLEncoder.encode("0:/gcodes/$name", "UTF-8")}", "POST").run {
                    doOutput = true; setFixedLengthStreamingMode(file.length())
                    outputStream.use { copy(file, it, progress) }
                    check()
                }
                if (print) open(c, "/rr_gcode?gcode=${URLEncoder.encode("M32 \"0:/gcodes/$name\"", "UTF-8")}").check()
            }
        }
    }

    fun status(c: HostConfig): HostStatus = when (c.type) {
        HostType.Moonraker -> {
            val s = get(c, "/printer/objects/query?print_stats&display_status&extruder&heater_bed").getJSONObject("result").getJSONObject("status")
            val ps = s.optJSONObject("print_stats") ?: JSONObject(); val ex = s.optJSONObject("extruder") ?: JSONObject(); val bed = s.optJSONObject("heater_bed") ?: JSONObject()
            HostStatus(ps.optString("state"), ps.optString("filename"), (s.optJSONObject("display_status")?.optDouble("progress") ?: 0.0) * 100,
                ex.optDouble("temperature"), ex.optDouble("target"), bed.optDouble("temperature"), bed.optDouble("target"), ps.optDouble("print_duration"))
        }
        HostType.OctoPrint -> {
            val job = get(c, "/api/job"); val pr = runCatching { get(c, "/api/printer") }.getOrNull()
            val t = pr?.optJSONObject("temperature")
            HostStatus(job.optString("state"), job.optJSONObject("job")?.optJSONObject("file")?.optString("name").orEmpty(),
                job.optJSONObject("progress")?.optDouble("completion", 0.0) ?: 0.0,
                t?.optJSONObject("tool0")?.optDouble("actual") ?: 0.0, t?.optJSONObject("tool0")?.optDouble("target") ?: 0.0,
                t?.optJSONObject("bed")?.optDouble("actual") ?: 0.0, t?.optJSONObject("bed")?.optDouble("target") ?: 0.0,
                job.optJSONObject("progress")?.optDouble("printTime", 0.0) ?: 0.0)
        }
        HostType.PrusaLink -> {
            val s = get(c, "/api/v1/status"); val p = s.optJSONObject("printer") ?: JSONObject(); val j = s.optJSONObject("job")
            HostStatus(p.optString("state"), "", j?.optDouble("progress", 0.0) ?: 0.0, p.optDouble("temp_nozzle"), p.optDouble("target_nozzle"),
                p.optDouble("temp_bed"), p.optDouble("target_bed"), j?.optDouble("time_printing", 0.0) ?: 0.0)
        }
        HostType.Duet -> {
            val s = get(c, "/rr_status?type=3")
            // RRF: temps.current = [bed, tool 0, ...]
            val cur = s.optJSONObject("temps")?.optJSONArray("current")
            HostStatus(s.optString("status"), "", s.optDouble("fractionPrinted", 0.0), cur?.optDouble(1) ?: 0.0, 0.0, cur?.optDouble(0) ?: 0.0, 0.0)
        }
    }

    /** cmd: pause, resume, cancel. */
    fun control(c: HostConfig, cmd: String) {
        when (c.type) {
            HostType.Moonraker -> post(c, "/printer/print/$cmd")
            HostType.OctoPrint -> post(c, "/api/job", if (cmd == "cancel") """{"command":"cancel"}""" else """{"command":"pause","action":"$cmd"}""")
            HostType.PrusaLink -> {
                val id = get(c, "/api/v1/job").optInt("id")
                open(c, if (cmd == "cancel") "/api/v1/job/$id" else "/api/v1/job/$id/$cmd", if (cmd == "cancel") "DELETE" else "PUT").check()
            }
            HostType.Duet -> open(c, "/rr_gcode?gcode=${URLEncoder.encode(when (cmd) { "pause" -> "M25"; "resume" -> "M24"; else -> "M0" }, "UTF-8")}").check()
        }
    }

    /** The host's usual camera stream, when none is set. */
    fun defaultCamera(c: HostConfig) = when (c.type) {
        HostType.OctoPrint, HostType.Moonraker -> "${c.base}/webcam/?action=stream"
        else -> ""
    }
}

/** Finds printers announcing themselves on the local network (mDNS / DNS-SD). */
class PrinterDiscovery(context: Context, private val onFound: (name: String, url: String, type: HostType) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val services = mapOf("_octoprint._tcp." to HostType.OctoPrint, "_moonraker._tcp." to HostType.Moonraker, "_prusa-link._tcp." to HostType.PrusaLink)

    fun start() = services.forEach { (service, type) ->
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = r.host?.hostAddress ?: return
                        onFound(r.serviceName, "http://$host:${r.port}", type)
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        listeners += l
        runCatching { nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear() }
}
