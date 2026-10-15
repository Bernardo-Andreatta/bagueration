package com.bernardo.ereader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Downloads a Piper voice from sherpa-onnx's GitHub releases (Wi-Fi only) and unpacks just the model
 * and tokens. The app's own espeak-ng data is reused, so the archive's 18 MB copy is skipped.
 */
object VoiceDownloader {

    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"

    @Volatile private var cancelled = false

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }

    fun cancel() { cancelled = true }

    /** [onProgress] gets 0..100 (or -1 if size unknown); all callbacks run on the main thread. */
    fun download(ctx: Context, v: VoiceInfo, onProgress: (Int) -> Unit, onDone: () -> Unit, onError: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        cancelled = false
        thread(name = "voice-download") {
            val target = Voices.piperDir(ctx, v)
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                tmp.deleteRecursively(); tmp.mkdirs()
                var url = URL(BASE + "vits-piper-${v.piper}.tar.bz2")
                var conn: HttpURLConnection
                var hops = 0
                while (true) {   // GitHub redirects to its file host
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200) error("server answered $code")
                    break
                }
                val total = conn.contentLengthLong
                var read = 0L
                var lastPct = -2
                val counting = object : FilterInputStream(conn.inputStream.buffered(1 shl 16)) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (cancelled) error("cancelled")
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            val pct = if (total > 0) (read * 100 / total).toInt() else -1
                            if (pct != lastPct) { lastPct = pct; main.post { onProgress(pct) } }
                        }
                        return n
                    }
                }
                TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val name = e.name.substringAfterLast('/')
                        if (e.isDirectory || "espeak-ng-data" in e.name) continue
                        if (!(name.endsWith(".onnx") || name == "tokens.txt" || name.endsWith(".json"))) continue
                        File(tmp, name).outputStream().use { out -> copy(tar, out) }
                    }
                }
                if (tmp.listFiles().orEmpty().none { it.name.endsWith(".onnx") } || !File(tmp, "tokens.txt").exists()) {
                    error("archive didn't contain a voice model")
                }
                File(tmp, ".complete").createNewFile()
                target.deleteRecursively()
                if (!tmp.renameTo(target)) error("couldn't save the voice")
                main.post(onDone)
            } catch (t: Throwable) {
                tmp.deleteRecursively()
                main.post { onError(if (cancelled) "Download cancelled" else (t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    /** Wi-Fi check, then a progress dialog with Cancel; [onDone] runs once the voice is installed. */
    fun withDialog(act: android.app.Activity, v: VoiceInfo, onDone: () -> Unit) {
        val th = Themes.current(act)
        val lang = Voices.LANGUAGES[v.lang] ?: v.lang
        if (!onWifi(act)) {
            Themes.dialog(act).setTitle("Wi-Fi needed")
                .setMessage("Connect to Wi-Fi to download ${v.name} (${v.downloadMb} MB).")
                .setPositiveButton("OK", null).show()
            return
        }
        val d = act.resources.displayMetrics.density
        val bar = android.widget.ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(th.accent)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(th.accent)
        }
        val label = android.widget.TextView(act).apply { text = "Starting…"; textSize = 13f; setTextColor(th.dim) }
        val box = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0)
            addView(bar); addView(label)
        }
        val dialog = Themes.dialog(act).setTitle("Downloading ${v.name} · $lang")
            .setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .show()
        download(act, v,
            onProgress = { pct ->
                if (pct >= 0) { bar.isIndeterminate = false; bar.progress = pct; label.text = "$pct% of ${v.downloadMb} MB" }
            },
            onDone = {
                dialog.dismiss()
                android.widget.Toast.makeText(act, "${v.name} installed", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            },
            onError = { msg ->
                dialog.dismiss()
                if (msg != "Download cancelled") Themes.dialog(act).setTitle("Download failed").setMessage(msg).setPositiveButton("OK", null).show()
            })
    }

    private fun copy(input: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            if (cancelled) error("cancelled")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }
}
