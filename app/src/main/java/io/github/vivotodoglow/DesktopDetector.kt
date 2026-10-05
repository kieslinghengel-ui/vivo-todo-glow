package io.github.vivotodoglow

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

/** Only package names are inspected in memory. Usage history is never persisted. */
class DesktopDetector(private val context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private var lastQuery = 0L
    private var foreground: String? = null
    private var refreshedHome = 0L
    private var home: String? = null
    fun foregroundPackage(): String? {
        if (!hasPermission(context)) { foreground = null; lastQuery = 0; return null }
        val time = now()
        try {
            val manager = context.getSystemService(UsageStatsManager::class.java)
            val events = manager.queryEvents(if (lastQuery == 0L) time - 60_000 else lastQuery, time)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == foreground) foreground = null
                }
            }
            lastQuery = time
            return foreground
        } catch (_: Exception) { foreground = null; return null }
    }
    fun isHome(packageName: String?): Boolean {
        val time = now()
        if (home == null || time - refreshedHome > 10_000) {
            home = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            refreshedHome = time
        }
        return packageName != null && packageName == home
    }
    fun reset() { lastQuery = 0; foreground = null }
    companion object {
        fun hasPermission(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
}
