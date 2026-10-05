package io.github.vivotodoglow.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val UNDO_WINDOW_MS = 5_000L
const val PANEL_LIMIT = 10

@Entity(tableName = "tasks")
data class TodoTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sortOrder: Long,
    val deleteAfter: Long? = null,
    val removalKind: String? = null
)

object TaskPolicy {
    fun active(tasks: List<TodoTask>) = tasks.filter { it.deleteAfter == null }.sortedBy { it.sortOrder }
    fun panel(tasks: List<TodoTask>) = active(tasks).take(PANEL_LIMIT)
    fun pending(tasks: List<TodoTask>, now: Long) = tasks.filter { (it.deleteAfter ?: 0) > now }
        .sortedBy { it.deleteAfter }
    fun secondsLeft(task: TodoTask, now: Long) = (((task.deleteAfter ?: now) - now + 999) / 1000).coerceAtLeast(0)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder, id") fun observe(): Flow<List<TodoTask>>
    @Query("SELECT * FROM tasks ORDER BY sortOrder, id") suspend fun all(): List<TodoTask>
    @Insert suspend fun insert(task: TodoTask): Long
    @Query("UPDATE tasks SET title = :title WHERE id = :id AND deleteAfter IS NULL") suspend fun rename(id: Long, title: String)
    @Query("UPDATE tasks SET deleteAfter = :deadline, removalKind = :kind WHERE id = :id AND deleteAfter IS NULL")
    suspend fun markRemoved(id: Long, deadline: Long, kind: String): Int
    @Query("UPDATE tasks SET deleteAfter = NULL, removalKind = NULL WHERE id = :id AND deleteAfter > :now")
    suspend fun undo(id: Long, now: Long): Int
    @Query("DELETE FROM tasks WHERE deleteAfter IS NOT NULL AND deleteAfter <= :now") suspend fun purge(now: Long)
    @Query("UPDATE tasks SET sortOrder = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Long)
}

@Database(entities = [TodoTask::class], version = 1, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, TaskDatabase::class.java, "glow-tasks.db").build()
    }
}

class TaskRepository(private val database: TaskDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.tasks()
    private val mutex = Mutex()
    val tasks = dao.observe()
    suspend fun cleanup() = mutex.withLock { dao.purge(now()) }
    suspend fun add(title: String) = mutex.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 200)
        database.withTransaction {
            dao.purge(now())
            dao.insert(TodoTask(title = clean, sortOrder = (dao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1024))
        }
    }
    suspend fun rename(id: Long, title: String) = mutex.withLock {
        val clean = title.trim()
        require(clean.isNotEmpty() && clean.length <= 200)
        dao.rename(id, clean)
    }
    suspend fun remove(id: Long, kind: String = "完成"): Boolean = mutex.withLock {
        dao.markRemoved(id, now() + UNDO_WINDOW_MS, kind) == 1
    }
    suspend fun undo(id: Long): Boolean = mutex.withLock {
        database.withTransaction {
            val time = now()
            dao.purge(time)
            dao.undo(id, time) == 1
        }
    }
    suspend fun move(id: Long, direction: Int) = mutex.withLock {
        database.withTransaction {
            val active = TaskPolicy.active(dao.all())
            val index = active.indexOfFirst { it.id == id }
            val other = index + direction
            if (index >= 0 && other in active.indices) {
                dao.setPosition(id, active[other].sortOrder)
                dao.setPosition(active[other].id, active[index].sortOrder)
            }
        }
    }
}
