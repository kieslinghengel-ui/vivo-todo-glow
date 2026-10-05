package io.github.vivotodoglow

import android.app.Application
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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
            repository.tasks.collect { snapshot.value = it }
        }
        scope.launch {
            while (isActive) {
                val pending = snapshot.value.filter { it.deleteAfter != null }
                if (pending.any { it.deleteAfter!! <= System.currentTimeMillis() }) repository.cleanup()
                delay(if (pending.isEmpty()) 1000 else 100)
            }
        }
    }
}
