package io.github.vivotodoglow.widget

import android.content.Context
import android.content.Intent
import java.util.UUID

/** Launcher entry is exported; only an app-created widget intent may auto-complete a task. */
internal object WidgetActions {
    private const val EXTRA_KEY = "widget_action_key"
    @Synchronized private fun key(context: Context): String {
        val prefs = context.getSharedPreferences("widget-actions", Context.MODE_PRIVATE)
        return prefs.getString("key", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("key", it).commit())
        }
    }
    fun authorize(context: Context, intent: Intent): Intent = intent.putExtra(EXTRA_KEY, key(context))
    fun isAuthorized(context: Context, intent: Intent): Boolean = intent.getStringExtra(EXTRA_KEY)?.let {
        it == key(context)
    } ?: false
    fun clear(intent: Intent) { intent.removeExtra(EXTRA_KEY) }
}
