package io.github.azuizui.stash

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Edge-to-edge fix for the inherited Round-Sync screens.
 *
 * With targetSdk 35 Android draws every activity edge-to-edge, so screens whose layouts were never
 * updated (Settings, About, Tasks, Triggers, Filters, ...) ended up under the status bar and the
 * navigation bar. This pads each activity's content by the system bars, display cutout and
 * keyboard. Activities that already handle insets themselves are left alone.
 */
class TeldriveApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name in HANDLES_OWN_INSETS) return
                val content = activity.findViewById<View>(android.R.id.content) ?: return
                ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                    val b = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout() or
                            WindowInsetsCompat.Type.ime()
                    )
                    v.setPadding(b.left, b.top, b.right, b.bottom)
                    WindowInsetsCompat.CONSUMED
                }
                ViewCompat.requestApplyInsets(content)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private companion object {
        val HANDLES_OWN_INSETS = setOf(
            "ca.pkay.rcloneexplorer.Activities.MainActivity",       // fitsSystemWindows layout
            "ca.pkay.rcloneexplorer.Activities.OnboardingActivity", // enableEdgeToEdge + own insets
            "io.github.azuizui.stash.TeldriveSetupActivity",
            "io.github.azuizui.stash.ChannelFoldersActivity",
            "io.github.azuizui.stash.ViewerActivity",
            "io.github.azuizui.stash.OfficeEditorActivity",                // bars drawn behind status/nav bar
        )
    }
}
