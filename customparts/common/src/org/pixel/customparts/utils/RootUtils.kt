package org.pixel.customparts.utils

import android.content.Context
import android.util.Log
import java.io.DataOutputStream

object RootUtils {
    private const val TAG = "RootUtils"

    private val REQUIRED_PERMISSIONS = arrayOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.STATUS_BAR",
        "android.permission.DEVICE_POWER",
        "android.permission.MONITOR_INPUT",
        "android.permission.MANAGE_ACTIVITY_TASKS",
        "android.permission.REAL_GET_TASKS",
        "android.permission.INTERACT_ACROSS_USERS_FULL",
        "android.permission.STATUS_BAR_SERVICE",
        "android.permission.INTERNAL_SYSTEM_WINDOW",
        "android.permission.INTERNET"
    )

    fun hasRootAccess(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        } finally {
            process?.destroy()
        }
    }

    fun grantPermissions(context: Context) {
        val packageName = context.packageName
        val commands = StringBuilder()
        
        for (perm in REQUIRED_PERMISSIONS) {
            if (context.checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Granting permission: $perm")
                commands.append("pm grant $packageName $perm\n")
            }
        }

        if (commands.isNotEmpty()) {
            commands.append("exit\n")
            runSuCommand(commands.toString())
        }
    }

    private fun runSuCommand(command: String) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes(command)
            os.flush()
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run su command", e)
        } finally {
            process?.destroy()
        }
    }
}   