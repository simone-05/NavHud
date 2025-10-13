package com.simonimal.navhud

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Date

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        saveCrashLogToFile(throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashLogToFile(throwable: Throwable) {
        val logFile = File(context.filesDir, "crash_log.txt")

        try {
            FileWriter(logFile, true).use { writer ->
                writer.appendLine("=== Crash at ${Date()} ===")
                writer.appendLine(Log.getStackTraceString(throwable))
                writer.appendLine()
            }
        } catch (e: IOException) {
            Log.e("CrashHandler", "Failed to write crash log", e)
        }
    }
}
