package dev.blazelight.p4oc.core.debug

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val CRASH_DIR = "crashes"
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val crashDir = File(context.filesDir, CRASH_DIR)
        crashDir.mkdirs()

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrash(context, thread, throwable)
            prevHandler?.uncaughtException(thread, throwable)
        }
    }

    fun saveCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR)
            crashDir.mkdirs()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            val fileName = "crash_${dateFormat.format(Date())}.txt"
            val file = File(crashDir, fileName)

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== CRASH ===")
            pw.println("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            pw.println("Thread: ${thread.name} (${thread.id})")
            pw.println("Thread group: ${thread.threadGroup?.name}")
            pw.println()
            throwable.printStackTrace(pw)
            pw.close()

            file.writeText(sw.toString())
        } catch (_: Throwable) { }
    }

    fun getCrashLogs(context: Context): List<CrashLog> {
        val crashDir = File(context.filesDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.map { CrashLog(it.name, it.readText(), it.lastModified()) }
            ?: emptyList()
    }

    fun clearCrashLogs(context: Context) {
        val crashDir = File(context.filesDir, CRASH_DIR)
        crashDir.listFiles()?.forEach { it.delete() }
    }

    data class CrashLog(
        val fileName: String,
        val content: String,
        val timestamp: Long
    )
}
