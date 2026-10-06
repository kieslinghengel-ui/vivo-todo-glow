package io.github.vivotodoglow

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DailyTaskRepositoryTest {
    private lateinit var database: TaskDatabase
    private lateinit var repo: TaskRepository
    private var time = 1_000_000L

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TaskDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskRepository(database) { time }
    }
    @After fun close() { database.close() }

    private suspend fun active() = TaskPolicy.active(database.tasks().all())
    private suspend fun latestUndoId() = -database.dailyCompletions().all().last().id

    @Test fun dailyAndCalendarDeadlineAreSavedAndEditable() = runBlocking {
        val day = LocalDate.of(2026, 10, 10).toEpochDay()
        val id = repo.add("  每日阅读  ", isDaily = true, dueDate = day)
        val task = database.tasks().find(id)!!
        assertEquals("每日阅读", task.title)
        assertTrue(task.isDaily)
        assertEquals(day, task.dueDate)
        assertTrue(repo.edit(id, "  阅读一本书  ", isDaily = false, dueDate = null))
        assertEquals("阅读一本书", database.tasks().find(id)!!.title)
        assertFalse(database.tasks().find(id)!!.isDaily)
        assertNull(database.tasks().find(id)!!.dueDate)
    }

    @Test fun arbitraryDragMoveKeepsOtherTasksInOrderAndPendingRestorable() = runBlocking {
        val ids = (1..7).map { repo.add("任务$it") }
        repo.remove(ids[2], "删除")
        assertTrue(repo.moveTo(ids[6], 0))
        assertEquals(listOf(7, 1, 2, 4, 5, 6), active().map { it.title.removePrefix("任务").toInt() })
        assertTrue(repo.moveTo(ids[0], 5))
        assertEquals(listOf(7, 2, 4, 5, 6, 1), active().map { it.title.removePrefix("任务").toInt() })
        assertTrue(repo.undo(ids[2]))
        assertEquals(7, active().size)
        assertEquals(ids.toSet(), active().map { it.id }.toSet())
    }

    @Test fun dailyHidesImmediatelyAndReturnsAsFifthAt800ms() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(6) { repo.add("普通任务${it + 1}") }
        assertTrue(repo.remove(daily))
        assertFalse(active().any { it.id == daily })
        val pending = TaskPolicy.pending(repo.tasks.first(), time).single()
        assertTrue(pending.id < 0)
        assertEquals("每日完成", pending.removalKind)
        time += 799
        repo.cleanup()
        assertFalse(active().any { it.id == daily })
        time += 1
        repo.cleanup()
        assertEquals(daily, active()[4].id)
        assertEquals(1, database.tasks().all().count { it.id == daily })
        assertEquals(1, TaskPolicy.pending(repo.tasks.first(), time).size)
    }

    @Test fun fewerThanFiveTasksAppendsDailyAndUndoRestoresItsOriginalPlace() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        val second = repo.add("任务二")
        val third = repo.add("任务三")
        repo.remove(daily)
        val undo = latestUndoId()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        assertEquals(listOf(second, third, daily), active().map { it.id })
        assertTrue(repo.undo(undo))
        assertEquals(listOf(daily, second, third), active().map { it.id })
        assertFalse(repo.undo(undo))
    }

    @Test fun dailyUndoBeforeAnimationFinishesCancelsReturnAndPreservesSingleTask() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(6) { repo.add("任务$it") }
        repo.remove(daily)
        val undo = latestUndoId()
        time += 400
        assertTrue(repo.undo(undo))
        time += 500
        repo.cleanup()
        assertEquals(daily, active().first().id)
        assertEquals(7, active().size)
        assertTrue(database.dailyCompletions().all().isEmpty())
    }

    @Test fun dailyUndoExpiresAtFiveSecondsWithoutDeletingTheTask() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(5) { repo.add("任务$it") }
        repo.remove(daily)
        val undo = latestUndoId()
        time += UNDO_WINDOW_MS
        repo.cleanup()
        assertFalse(repo.undo(undo))
        assertEquals(daily, active()[4].id)
        assertTrue(database.dailyCompletions().all().isEmpty())
    }

    @Test fun concurrentDailyTapsDoNotDuplicateTaskOrEvents() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        val results = (1..8).map { async(Dispatchers.Default) { repo.remove(daily) } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, database.dailyCompletions().all().size)
        assertEquals(1, database.tasks().all().size)
    }

    @Test fun separateDailyUndoWindowsRestoreOriginalOrderIndependently() = runBlocking {
        val first = repo.add("每日一", isDaily = true)
        val second = repo.add("每日二", isDaily = true)
        repeat(6) { repo.add("任务$it") }
        repo.remove(first)
        val firstUndo = latestUndoId()
        time += 200
        repo.remove(second)
        val secondUndo = latestUndoId()
        time += 800
        repo.cleanup()
        assertTrue(repo.undo(firstUndo))
        assertTrue(repo.undo(secondUndo))
        assertEquals(listOf(first, second), active().take(2).map { it.id })
        assertEquals(8, active().size)
        assertTrue(database.dailyCompletions().all().isEmpty())
    }

    @Test fun denseDailyReturnsAndUndoKeepSortKeysUniqueAndDragWorking() = runBlocking {
        val dailyIds = mutableListOf<Long>()
        repeat(3) { dailyIds += repo.add("每日$it", isDaily = true) }
        val ordinary = repo.add("等待撤销的普通任务")
        repeat(97) { dailyIds += repo.add("每日${it + 3}", isDaily = true) }
        repeat(6) { repo.add("普通任务$it") }
        repo.remove(ordinary)
        dailyIds.forEach { assertTrue(repo.remove(it)) }
        val events = database.dailyCompletions().all()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        assertEquals(106, active().size)
        assertTrue(repo.undo(ordinary))
        events.forEach { assertTrue(repo.undo(-it.id)) }
        val restored = active()
        assertEquals(107, restored.size)
        assertEquals(107, restored.map { it.id }.distinct().size)
        assertEquals(107, restored.map { it.sortOrder }.distinct().size)
        val last = restored.last().id
        assertTrue(repo.moveTo(last, 0))
        assertEquals(last, active().first().id)
        assertTrue(database.dailyCompletions().all().isEmpty())
    }

    @Test fun undoLatestRepeatedCompletionKeepsOlderWindowUsable() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(6) { repo.add("任务$it") }
        repo.remove(daily)
        val firstUndo = latestUndoId()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        repo.remove(daily)
        val secondUndo = latestUndoId()
        assertTrue(repo.undo(secondUndo))
        assertEquals(daily, active()[4].id)
        assertEquals(1, database.dailyCompletions().all().size)
        assertTrue(repo.undo(firstUndo))
        assertEquals(daily, active().first().id)
        assertEquals(7, active().size)
    }

    @Test fun undoOlderRepeatedCompletionInvalidatesLaterEventAndDoesNotDuplicate() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(6) { repo.add("任务$it") }
        repo.remove(daily)
        val firstUndo = latestUndoId()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        repo.remove(daily)
        val secondUndo = latestUndoId()
        assertTrue(repo.undo(firstUndo))
        assertFalse(repo.undo(secondUndo))
        assertEquals(daily, active().first().id)
        assertEquals(7, active().size)
        time += 10_000
        repo.cleanup()
        assertEquals(daily, active().first().id)
    }

    @Test fun explicitDailyDeletionSupersedesCompletionUndoAndDeletesAfterFiveSeconds() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repo.remove(daily)
        val completionUndo = latestUndoId()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        assertTrue(repo.remove(daily, "删除"))
        assertFalse(repo.undo(completionUndo))
        assertTrue(database.dailyCompletions().all().isEmpty())
        time += UNDO_WINDOW_MS
        repo.cleanup()
        assertTrue(database.tasks().all().isEmpty())
    }

    @Test fun completionUndoPreservesEditsMadeAfterDailyReturns() = runBlocking {
        val daily = repo.add("每日任务", isDaily = true)
        repeat(6) { repo.add("任务$it") }
        repo.remove(daily)
        val undo = latestUndoId()
        time += DAILY_RETURN_DELAY_MS
        repo.cleanup()
        val due = LocalDate.of(2026, 10, 12).toEpochDay()
        assertTrue(repo.edit(daily, "已编辑的普通任务", isDaily = false, dueDate = due))
        assertTrue(repo.undo(undo))
        assertEquals("已编辑的普通任务", active().first().title)
        assertFalse(active().first().isDaily)
        assertEquals(due, active().first().dueDate)
    }

    @Test fun reopeningDatabaseFinishesDailyReturnEvenAfterUndoExpiry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "daily-restart-${java.util.UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, TaskDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val before = TaskRepository(disk) { time }
            val daily = before.add("每日任务", isDaily = true, dueDate = 20_731)
            repeat(6) { before.add("任务$it") }
            before.remove(daily)
            disk.close()
            time += 10_000
            disk = Room.databaseBuilder(context, TaskDatabase::class.java, name).allowMainThreadQueries().build()
            TaskRepository(disk) { time }.cleanup()
            val restored = TaskPolicy.active(disk.tasks().all())
            assertEquals(daily, restored[4].id)
            assertEquals(20_731L, restored[4].dueDate)
            assertEquals(7, restored.size)
            assertTrue(disk.dailyCompletions().all().isEmpty())
        } finally { disk.close(); context.deleteDatabase(name) }
    }

    @Test fun versionOneMigrationPreservesTitlesOrderAndPendingDeadline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, sortOrder INTEGER NOT NULL, deleteAfter INTEGER, removalKind TEXT)")
            old.execSQL("INSERT INTO tasks VALUES (1, '保留原任务', 1024, NULL, NULL)")
            old.execSQL("INSERT INTO tasks VALUES (2, '保留撤销窗口', 2048, 1004000, '完成')")
            old.version = 1
        }
        val upgraded = Room.databaseBuilder(context, TaskDatabase::class.java, name)
            .allowMainThreadQueries().addMigrations(TaskDatabase.MIGRATION_1_2).build()
        try {
            val tasks = upgraded.tasks().all()
            assertEquals(listOf("保留原任务", "保留撤销窗口"), tasks.map { it.title })
            assertEquals(listOf(1024L, 2048L), tasks.map { it.sortOrder })
            assertTrue(tasks.none { it.isDaily })
            assertTrue(tasks.all { it.dueDate == null && it.hiddenUntil == null })
            assertEquals(1_004_000L, tasks[1].deleteAfter)
            assertTrue(TaskRepository(upgraded) { time }.undo(2))
            assertEquals(2, TaskPolicy.active(upgraded.tasks().all()).size)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
