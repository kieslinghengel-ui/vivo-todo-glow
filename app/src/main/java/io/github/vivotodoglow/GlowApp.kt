package io.github.vivotodoglow

import android.app.Application
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import io.github.vivotodoglow.widget.GlowWidgetProvider

class GlowApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var repository: TaskRepository
    lateinit var settings: SettingsStore
    val snapshot = MutableStateFlow<List<TodoTask>>(emptyList())
    override fun onCreate() {
        super.onCreate()
        repository = TaskRepository(TaskDatabase.open(this))
        settings = SettingsStore(this)
        scope.launch {
            repository.cleanup()
            repository.tasks.collect {
                snapshot.value = it
                GlowWidgetProvider.refresh(this@GlowApp, it)
            }
        }
        scope.launch {
            while (isActive) {
                val pending = snapshot.value.filter { it.deleteAfter != null || it.hiddenUntil != null }
                val time = System.currentTimeMillis()
                if (pending.any { (it.deleteAfter != null && it.deleteAfter <= time) ||
                        (it.hiddenUntil != null && it.hiddenUntil <= time) }) repository.cleanup()
                delay(if (pending.isEmpty()) 1000 else 100)
            }
        }
    }
}
