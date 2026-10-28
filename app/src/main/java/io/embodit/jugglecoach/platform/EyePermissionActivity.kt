package io.embodit.jugglecoach.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Transparent helper for the eye-tracking permission `com.picovr.permission.EYE_TRACKING` (SDK doc "Eye tracking": a
 * runtime permission; RehearsalHall v0.1.2 does the same). Refused or no dialog: the gaze falls back to the head
 * direction (S13) and the menu's gaze row offers "Ask again" / "Open settings".
 */
class EyePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (granted(this)) {
            onResult?.invoke(true, false)
            finish()
            return
        }
        requestPermissions(arrayOf(PERMISSION), REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        // refused without a rationale = "don't ask again": the menu then offers the system settings
        val permanent = !ok && !shouldShowRequestPermissionRationale(PERMISSION)
        JcLog.i("EYE permission granted=$ok permanent=$permanent")
        onResult?.invoke(ok, permanent)
        finish()
    }

    companion object {
        const val PERMISSION = "com.picovr.permission.EYE_TRACKING"
        private const val REQUEST = 61

        /** (granted, permanently refused) */
        @Volatile var onResult: ((Boolean, Boolean) -> Unit)? = null

        fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun open(context: Context): Boolean = runCatching { context.startActivity(Intent(context, EyePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

        /** The app's system settings page (permission toggles live there). */
        fun openSettings(context: Context): Boolean =
            runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .isSuccess
    }
}
