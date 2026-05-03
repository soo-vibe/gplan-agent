package com.example.gplanagent.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Centralizes the three permission checks the app needs.
 * SMS read/receive — runtime, dangerous.
 * Notification listener access — special, set in system Settings.
 * Ignore battery optimizations — special, system dialog.
 */
object PermissionStatus {

    fun smsGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun contactsGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun notificationListenerGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.startsWith("${ctx.packageName}/") }
    }

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun allGranted(ctx: Context): Boolean =
        smsGranted(ctx) && notificationListenerGranted(ctx) && batteryUnrestricted(ctx)
}
