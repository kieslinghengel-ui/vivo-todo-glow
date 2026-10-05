package io.github.vivotodoglow.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*

private val Context.glowSettings by preferencesDataStore("glow-settings")
data class GlowSettings(val opacity: Int = 88, val x: Int = 16, val y: Int = 80)
class SettingsStore(private val context: Context) {
    private val opacity = intPreferencesKey("opacity")
    private val x = intPreferencesKey("panel_x")
    private val y = intPreferencesKey("panel_y")
    val settings: Flow<GlowSettings> = context.glowSettings.data.map {
        GlowSettings((it[opacity] ?: 88).coerceIn(35, 100), it[x] ?: 16, it[y] ?: 80)
    }
    suspend fun opacity(value: Int) { context.glowSettings.edit { it[opacity] = value.coerceIn(35, 100) } }
    suspend fun position(px: Int, py: Int) { context.glowSettings.edit { it[x] = px; it[y] = py } }
}
