package io.github.vivotodoglow

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DesktopDetectorTest {
    @Test fun tracksHomeAndApplicationTransitionsWithoutSavingUsageHistory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        val home = "com.example.launcher"
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = home; name = "$home.Home"
                applicationInfo = ApplicationInfo().apply { packageName = home }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), info)
        var time = 1_000_000L
        val usage = shadowOf(context.getSystemService(UsageStatsManager::class.java))
        usage.addEvent(home, time - 100, UsageEvents.Event.ACTIVITY_RESUMED)
        val detector = DesktopDetector(context) { time }
        assertEquals(home, detector.foregroundPackage())
        assertTrue(detector.isHome(home))
        time += 1_000
        usage.addEvent(home, time - 200, UsageEvents.Event.ACTIVITY_PAUSED)
        usage.addEvent("com.example.browser", time - 100, UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("com.example.browser", detector.foregroundPackage())
        assertFalse(detector.isHome("com.example.browser"))
        time += 1_000
        assertEquals("com.example.browser", detector.foregroundPackage())
    }
    @Test fun deniedOrRevokedUsageAccessReturnsUnknownSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ops = shadowOf(context.getSystemService(AppOpsManager::class.java))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        val detector = DesktopDetector(context) { 1_000_000L }
        assertFalse(DesktopDetector.hasPermission(context))
        assertNull(detector.foregroundPackage())
        assertFalse(detector.isHome(null))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(context.getSystemService(UsageStatsManager::class.java)).addEvent("com.example.app", 999_900, UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("com.example.app", detector.foregroundPackage())
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertNull(detector.foregroundPackage())
    }
}
