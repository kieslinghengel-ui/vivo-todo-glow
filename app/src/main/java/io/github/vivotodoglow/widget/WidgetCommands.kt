package io.github.vivotodoglow.widget

import io.github.vivotodoglow.data.TaskRepository

internal object WidgetCommands {
    suspend fun execute(repository: TaskRepository, operation: String?, id: Long) {
        when (operation) {
            "complete" -> if (id > 0) repository.remove(id)
            "up" -> if (id > 0) repository.move(id, -1)
            "down" -> if (id > 0) repository.move(id, 1)
        }
    }
}
