package io.github.vivotodoglow.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val UNDO_WINDOW_MS = 5_000L
const val DAILY_RETURN_DELAY_MS = 800L
const val PANEL_LIMIT = 10

@Entity(tableName = "tasks")
data class TodoTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sortOrder: Long,
    val deleteAfter: Long? = null,
    val removalKind: String? = null,
    @ColumnInfo(defaultValue = "0") val isDaily: Boolean = false,
    /** A local calendar date from LocalDate.toEpochDay(), never a UTC timestamp. */
    val dueDate: Long? = null,
    val hiddenUntil: Long? = null
)

/** Daily completion is an event, not a second copy of the task. Negative snapshot IDs identify these events. */
@Entity(tableName = "daily_completions", indices = [Index("taskId")])
data class DailyCompletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val dueDate: Long?,
    val originalSortOrder: Long,
    val originalIndex: Int,
    val undoUntil: Long
)

object TaskPolicy {
    fun active(tasks: List<TodoTask>) = tasks.filter { it.deleteAfter == null && it.hiddenUntil == null }
        .sortedWith(compareBy<TodoTask> { it.sortOrder }.thenBy { it.id })
    fun panel(tasks: List<TodoTask>) = active(tasks).take(PANEL_LIMIT)
    fun pending(tasks: List<TodoTask>, now: Long) = tasks.filter { (it.deleteAfter ?: 0) > now }
        .sortedBy { it.deleteAfter }
    fun secondsLeft(task: TodoTask, now: Long) = (((task.deleteAfter ?: now) - now + 999) / 1000).coerceAtLeast(0)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY sortOrder, id") fun observe(): Flow<List<TodoTask>>
    // A single query gives the UI one consistent snapshot across both tables after each transaction.
    @Query("""
        SELECT id, title, sortOrder, deleteAfter, removalKind, isDaily, dueDate, hiddenUntil FROM tasks
        UNION ALL
        SELECT -id, title, originalSortOrder, undoUntil, '每日完成', 1, dueDate, NULL FROM daily_completions
        ORDER BY sortOrder, id
    """) fun observeSnapshot(): Flow<List<TodoTask>>
    @Query("SELECT * FROM tasks ORDER BY sortOrder, id") suspend fun all(): List<TodoTask>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun find(id: Long): TodoTask?
    @Insert suspend fun insert(task: TodoTask): Long
    @Query("UPDATE tasks SET title = :title WHERE id = :id AND deleteAfter IS NULL AND hiddenUntil IS NULL")
    suspend fun rename(id: Long, title: String)
    @Query("UPDATE tasks SET title = :title, isDaily = :isDaily, dueDate = :dueDate WHERE id = :id AND deleteAfter IS NULL AND hiddenUntil IS NULL")
    suspend fun edit(id: Long, title: String, isDaily: Boolean, dueDate: Long?): Int
    @Query("UPDATE tasks SET deleteAfter = :deadline, removalKind = :kind WHERE id = :id AND deleteAfter IS NULL AND hiddenUntil IS NULL")
    suspend fun markRemoved(id: Long, deadline: Long, kind: String): Int
    @Query("UPDATE tasks SET hiddenUntil = :until WHERE id = :id AND deleteAfter IS NULL AND hiddenUntil IS NULL")
    suspend fun hideDaily(id: Long, until: Long): Int
    @Query("UPDATE tasks SET hiddenUntil = NULL WHERE id = :id") suspend fun showDaily(id: Long)
    @Query("UPDATE tasks SET deleteAfter = NULL, removalKind = NULL WHERE id = :id AND deleteAfter > :now")
    suspend fun undo(id: Long, now: Long): Int
    @Query("DELETE FROM tasks WHERE deleteAfter IS NOT NULL AND deleteAfter <= :now") suspend fun purge(now: Long)
    @Query("UPDATE tasks SET sortOrder = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Long)
}

@Dao
interface DailyCompletionDao {
    @Insert suspend fun insert(event: DailyCompletion): Long
    @Query("SELECT * FROM daily_completions WHERE id = :id") suspend fun find(id: Long): DailyCompletion?
    @Query("SELECT * FROM daily_completions ORDER BY id") suspend fun all(): List<DailyCompletion>
    @Query("DELETE FROM daily_completions WHERE taskId = :taskId") suspend fun discardForTask(taskId: Long)
    @Query("DELETE FROM daily_completions WHERE taskId = :taskId AND id >= :fromId")
    suspend fun discardFrom(taskId: Long, fromId: Long)
    @Query("DELETE FROM daily_completions WHERE undoUntil <= :now") suspend fun purge(now: Long)
}

@Database(entities = [TodoTask::class, DailyCompletion::class], version = 2, exportSchema = true)
abstract class TaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun dailyCompletions(): DailyCompletionDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN isDaily INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN dueDate INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN hiddenUntil INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_completions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, taskId INTEGER NOT NULL, title TEXT NOT NULL, dueDate INTEGER, originalSortOrder INTEGER NOT NULL, originalIndex INTEGER NOT NULL, undoUntil INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_daily_completions_taskId ON daily_completions (taskId)")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, TaskDatabase::class.java, "glow-tasks.db")
            .addMigrations(MIGRATION_1_2).build()
    }
}

class TaskRepository(private val database: TaskDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = database.tasks()
    private val completions = database.dailyCompletions()
    private val mutex = Mutex()
    val tasks = dao.observeSnapshot()

    suspend fun cleanup() = mutex.withLock { database.withTransaction { cleanupAt(now()) } }

    suspend fun add(title: String, isDaily: Boolean = false, dueDate: Long? = null) = mutex.withLock {
        val clean = validatedTitle(title)
        database.withTransaction {
            cleanupAt(now())
            dao.insert(TodoTask(title = clean, sortOrder = (dao.all().maxOfOrNull { it.sortOrder } ?: 0) + 1024,
                isDaily = isDaily, dueDate = dueDate))
        }
    }

    suspend fun rename(id: Long, title: String) = mutex.withLock { dao.rename(id, validatedTitle(title)) }

    suspend fun edit(id: Long, title: String, isDaily: Boolean = false, dueDate: Long? = null): Boolean = mutex.withLock {
        dao.edit(id, validatedTitle(title), isDaily, dueDate) == 1
    }

    suspend fun remove(id: Long, kind: String = "完成"): Boolean = mutex.withLock {
        database.withTransaction {
            val time = now()
            cleanupAt(time)
            val task = dao.find(id) ?: return@withTransaction false
            if (task.deleteAfter != null || task.hiddenUntil != null) return@withTransaction false
            if (task.isDaily && (kind == "完成" || kind == "每日完成")) {
                val active = TaskPolicy.active(dao.all())
                completions.insert(DailyCompletion(taskId = id, title = task.title, dueDate = task.dueDate,
                    originalSortOrder = task.sortOrder, originalIndex = active.indexOfFirst { it.id == id },
                    undoUntil = time + UNDO_WINDOW_MS))
                dao.hideDaily(id, time + DAILY_RETURN_DELAY_MS) == 1
            } else {
                // An explicit deletion supersedes completion events; those events must never resurrect it.
                completions.discardForTask(id)
                dao.markRemoved(id, time + UNDO_WINDOW_MS, kind) == 1
            }
        }
    }

    suspend fun undo(id: Long): Boolean = mutex.withLock {
        database.withTransaction {
            val time = now()
            cleanupAt(time)
            if (id >= 0) {
                val task = dao.find(id) ?: return@withTransaction false
                if (dao.undo(id, time) != 1) return@withTransaction false
                // Many daily returns can rebalance the surrounding keys while this task is hidden.
                // Restore ahead of a reused anchor, rather than create duplicate active sort keys.
                restoreSortAnchor(id, task.sortOrder)
                return@withTransaction true
            }
            val event = completions.find(-id) ?: return@withTransaction false
            if (event.undoUntil <= time) return@withTransaction false
            val task = dao.find(event.taskId)
            if (task == null || task.deleteAfter != null) {
                completions.discardForTask(event.taskId)
                return@withTransaction false
            }
            // Repeated daily completions form one history. Undoing an older one also cancels later ones.
            // This restores the one real task, preserves any edits, and cannot create duplicate tasks.
            completions.discardFrom(event.taskId, event.id)
            dao.showDaily(event.taskId)
            restoreSortAnchor(event.taskId, event.originalSortOrder)
            true
        }
    }

    suspend fun moveTo(id: Long, targetIndex: Int): Boolean = mutex.withLock {
        database.withTransaction {
            cleanupAt(now())
            reorder(id, targetIndex)
        }
    }

    suspend fun move(id: Long, direction: Int) = mutex.withLock {
        database.withTransaction {
            cleanupAt(now())
            val active = TaskPolicy.active(dao.all())
            val index = active.indexOfFirst { it.id == id }
            val target = index + direction
            if (index >= 0 && target in active.indices) reorder(id, target)
        }
    }

    private fun validatedTitle(title: String): String = title.trim().also {
        require(it.isNotEmpty() && it.length <= 200)
    }

    private suspend fun cleanupAt(time: Long) {
        val returning = dao.all().filter { it.hiddenUntil != null && it.hiddenUntil <= time && it.deleteAfter == null }
            .sortedWith(compareBy<TodoTask> { it.hiddenUntil }.thenBy { it.id })
        for (task in returning) {
            dao.showDaily(task.id)
            insertAt(task.id, 4)
        }
        dao.purge(time)
        completions.purge(time)
    }

    /** Reuse the active sort slots, so an ordinary pending removal keeps its original anchor. */
    private suspend fun reorder(id: Long, targetIndex: Int): Boolean {
        val active = TaskPolicy.active(dao.all())
        val index = active.indexOfFirst { it.id == id }
        if (index < 0 || active.isEmpty()) return false
        val target = targetIndex.coerceIn(0, active.lastIndex)
        if (target == index) return true
        val reordered = active.toMutableList().apply { add(target, removeAt(index)) }
        reordered.forEachIndexed { i, task -> dao.setPosition(task.id, active[i].sortOrder) }
        return true
    }

    private suspend fun insertAt(id: Long, targetIndex: Int) {
        var others = TaskPolicy.active(dao.all()).filter { it.id != id }
        val target = targetIndex.coerceIn(0, others.size)
        // Usually there is ample room between sort keys. Rebalance only when many inserts exhaust it.
        if (target > 0 && target < others.size && others[target].sortOrder - others[target - 1].sortOrder < 2) {
            others.forEachIndexed { i, task -> dao.setPosition(task.id, (i + 1L) * 1024) }
            others = TaskPolicy.active(dao.all()).filter { it.id != id }
        }
        val key = when {
            others.isEmpty() -> 1024L
            target == 0 -> others.first().sortOrder - 1024
            target == others.size -> others.last().sortOrder + 1024
            else -> others[target - 1].sortOrder + (others[target].sortOrder - others[target - 1].sortOrder) / 2
        }
        dao.setPosition(id, key)
    }

    private suspend fun restoreSortAnchor(id: Long, originalKey: Long) {
        val others = TaskPolicy.active(dao.all()).filter { it.id != id }
        if (others.none { it.sortOrder == originalKey }) {
            dao.setPosition(id, originalKey)
        } else {
            // Another drag may have reused the old slot; insert ahead of that slot rather than tie keys.
            val index = others.indexOfFirst { it.sortOrder >= originalKey }.let { if (it < 0) others.size else it }
            insertAt(id, index)
        }
    }
}
