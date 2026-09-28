package acr.browser.lightning.browser.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlin.math.sqrt

/**
 * Detects TV / set-top box style devices, including many Chinese Android TVs that are not Google TV
 * and often omit Leanback feature flags.
 */
object TvDevice {

    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            || pm.hasSystemFeature("android.software.leanback")
        ) {
            return true
        }
        @Suppress("DEPRECATION")
        if (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
            return true
        }
        // Domestic TCL / other Android TVs: large panel + no touchscreen capability.
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return true
        }
        val metrics = context.resources.displayMetrics
        val widthInches = metrics.widthPixels / metrics.xdpi
        val heightInches = metrics.heightPixels / metrics.ydpi
        val diagonal = sqrt(widthInches * widthInches + heightInches * heightInches)
        return diagonal >= 20f && metrics.densityDpi <= 320
    }
}
