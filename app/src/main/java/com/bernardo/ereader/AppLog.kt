package com.bernardo.ereader

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Battery-optimization state: Samsung/Android throttle background work much harder unless the app is unrestricted. */
object Background {
    fun unrestricted(ctx: Context) =
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Opens Android's own "let this app run unrestricted" prompt. */
    fun request(act: Activity) {
        runCatching {
            act.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${act.packageName}")))
        }.onFailure {
            runCatching { act.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}

/** Installs the crash handler and records how the previous run ended, before anything else starts. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}

/**
 * Simple text log for debugging on other people's phones: app events, errors, crashes, and (Android 11+)
 * how the previous run ended — which also catches native engine crashes and low-memory kills that never
 * reach Java. Shared as plain text from Settings / Library.
 */
object AppLog {
    private const val MAX_BYTES = 400_000L
    private lateinit var file: File
    private lateinit var appCtx: Context
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(app: Application) {
        appCtx = app
        val dir = File(app.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        rotate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                write("CRASH", "Uncaught exception on thread ${thread.name}\n${stack(t)}")
                markCrash()
            }
            previous?.uncaughtException(thread, t)
        }
        write("APP", "---- launch: ${versionName()} on ${deviceLine()}")
        recordPreviousExit()
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        write("$tag/ERROR", if (t == null) msg else "$msg\n${stack(t)}")
    }

    // ---------- crash prompt ----------

    private fun crashFlag() = File(file.parentFile, "crashed")
    private fun markCrash() { crashFlag().createNewFile() }

    /** True once after a run that crashed (Java crash, native crash or low-memory kill). */
    fun consumeCrashFlag(): Boolean = crashFlag().let { f -> f.exists().also { if (it) f.delete() } }

    // ---------- report ----------

    fun report(): String {
        val sb = StringBuilder()
        sb.append("bagueration debug log\n")
        sb.append("App: ").append(versionName()).append('\n')
        sb.append("Device: ").append(deviceLine()).append('\n')
        val am = appCtx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        sb.append("RAM: total ${mem.totalMem / 1_048_576} MB, available ${mem.availMem / 1_048_576} MB, low=${mem.lowMemory}\n")
        sb.append("App memory class: ${am.memoryClass} MB (large ${am.largeMemoryClass} MB)\n")
        sb.append("Storage free: ${appCtx.filesDir.usableSpace / 1_048_576} MB\n")
        sb.append("Battery: ${if (Background.unrestricted(appCtx)) "unrestricted" else "optimized (background work throttled)"}\n\n")
        sb.append(runCatching { file.readText() }.getOrDefault("(log unreadable)"))
        return sb.toString()
    }

    /** Opens the share sheet with the log as text (tail only, share intents have a size limit). */
    fun share(act: Activity) {
        val text = report().let { if (it.length > 180_000) "…(older lines trimmed)\n" + it.takeLast(180_000) else it }
        act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "bagueration debug log")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Send debug log"))
    }

    /** Offer to send the log right after a crashed run. */
    fun offerAfterCrash(act: Activity) {
        if (!consumeCrashFlag()) return
        Themes.dialog(act).setTitle("bagueration closed unexpectedly")
            .setMessage("It stopped working last time. Send the debug log so the problem can be found?")
            .setPositiveButton("Send log") { _, _ -> share(act) }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------- internals ----------

    @Synchronized private fun write(tag: String, msg: String) {
        if (!::file.isInitialized) return
        if (file.length() > MAX_BYTES * 3 / 2) rotate()
        runCatching { file.appendText("${time.format(Date())} [${Thread.currentThread().name}] $tag: $msg\n") }
    }

    private fun rotate() {
        if (file.length() > MAX_BYTES) runCatching {
            val keep = file.readText().takeLast((MAX_BYTES / 2).toInt())
            file.writeText("…(older log trimmed)\n$keep")
        }
    }

    private fun stack(t: Throwable) = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun versionName() = runCatching {
        appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" }
    }.getOrDefault("?")

    private fun deviceLine() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
        "CPU ${Build.SUPPORTED_ABIS.joinToString("/")}"

    /** How the last run ended (Android 11+): crashes in native code and low-memory kills show up here. */
    private fun recordPreviousExit() {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = appCtx.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(appCtx.packageName, 0, 1).firstOrNull() ?: return
            val reason = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH -> "app crash (Java)"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash in native code (voice engine)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: phone ran out of memory"
                ApplicationExitInfo.REASON_ANR -> "froze (not responding)"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
                ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${last.status}"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by user / force stop"
                ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
                ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
                ApplicationExitInfo.REASON_OTHER -> "other (often app update)"
                else -> "reason ${last.reason}"
            }
            val whenStr = time.format(Date(last.timestamp))
            write("PREVIOUS-EXIT", "$whenStr: $reason · ${last.description ?: ""} · memory pss ${last.pss / 1024} MB, rss ${last.rss / 1024} MB")
            val bad = last.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
            if (bad) {
                markCrash()
                // ANR traces are text; native tombstones are binary on newer Android, so only keep text ones
                if (last.reason == ApplicationExitInfo.REASON_ANR) last.traceInputStream?.use { s ->
                    write("PREVIOUS-EXIT", "trace:\n" + s.readBytes().toString(Charsets.UTF_8).take(20_000))
                }
            }
        }.onFailure { write("APP", "couldn't read previous exit: ${it.message}") }
    }
}
