package io.github.vivotodoglow

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class TaskRepositoryTest {
    private lateinit var database: TaskDatabase
    private lateinit var repo: TaskRepository
    private var time = 1_000_000L
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TaskDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TaskRepository(database) { time }
    }
    @After fun close() { database.close() }
    @Test fun panelShowsTenAndEleventhFillsVacancy() = runBlocking {
        assertTrue(TaskPolicy.panel(database.tasks().all()).isEmpty())
        val first = repo.add("任务1")
        assertEquals(1, TaskPolicy.panel(database.tasks().all()).size)
        repeat(10) { repo.add("任务${it + 2}") }
        val tasks = database.tasks().all()
        assertEquals(11, TaskPolicy.active(tasks).size)
        assertEquals(10, TaskPolicy.panel(tasks).size)
        assertFalse(TaskPolicy.panel(tasks).any { it.title == "任务11" })
        repo.remove(first)
        assertEquals("任务11", TaskPolicy.panel(database.tasks().all()).last().title)
    }
    @Test fun undoAt4999msRestoresOriginalPosition() = runBlocking {
        repo.add("第一项")
        val middle = repo.add("第二项")
        repo.add("第三项")
        repo.remove(middle)
        time += 4_999
        assertTrue(repo.undo(middle))
        assertEquals(listOf("第一项", "第二项", "第三项"), TaskPolicy.active(database.tasks().all()).map { it.title })
    }
    @Test fun undoAtExactlyFiveSecondsFailsAndPurges() = runBlocking {
        val id = repo.add("超时任务")
        repo.remove(id)
        time += UNDO_WINDOW_MS
        assertFalse(repo.undo(id))
        assertTrue(database.tasks().all().isEmpty())
    }
    @Test fun repeatedCompletionDoesNotExtendDeadline() = runBlocking {
        val id = repo.add("不能重复删除")
        assertTrue(repo.remove(id))
        val deadline = database.tasks().all().single().deleteAfter
        time += 1000
        assertFalse(repo.remove(id))
        assertEquals(deadline, database.tasks().all().single().deleteAfter)
    }
    @Test fun independentWindowsAndConcurrentTaps() = runBlocking {
        val first = repo.add("先完成")
        val second = repo.add("后完成")
        val results = (1..8).map { async(Dispatchers.Default) { repo.remove(first) } }.awaitAll()
        assertEquals(1, results.count { it })
        time += 2_000
        repo.remove(second, "删除")
        time += 3_000
        repo.cleanup()
        assertFalse(repo.undo(first))
        assertTrue(repo.undo(second))
        assertEquals("后完成", database.tasks().all().single().title)
    }
    @Test fun restartedRepositoryHonorsPersistedDeadline() = runBlocking {
        val id = repo.add("重启保留窗口")
        repo.remove(id)
        time += 4_000
        val restarted = TaskRepository(database) { time }
        restarted.cleanup()
        assertEquals(1, database.tasks().all().size)
        time += 1_000
        restarted.cleanup()
        assertTrue(database.tasks().all().isEmpty())
    }
    @Test fun diskDatabaseReopensWithDeadlineAndTitle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "restart-${java.util.UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, TaskDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val before = TaskRepository(disk) { time }
            val id = before.add("真正关闭数据库后恢复")
            before.remove(id)
            val deadline = disk.tasks().all().single().deleteAfter
            disk.close()
            time += 4_000
            disk = Room.databaseBuilder(context, TaskDatabase::class.java, name).allowMainThreadQueries().build()
            val after = TaskRepository(disk) { time }
            after.cleanup()
            assertEquals(deadline, disk.tasks().all().single().deleteAfter)
            assertTrue(after.undo(id))
            assertEquals("真正关闭数据库后恢复", disk.tasks().all().single().title)
        } finally { disk.close(); context.deleteDatabase(name) }
    }
    @Test fun renameAndReorderKeepPendingTaskRestorable() = runBlocking {
        val a = repo.add("A")
        val b = repo.add("B")
        val c = repo.add("C")
        repo.remove(b)
        repo.move(c, -1)
        repo.rename(a, "编辑A")
        repo.undo(b)
        assertEquals(listOf("C", "B", "编辑A"), TaskPolicy.active(database.tasks().all()).map { it.title })
    }
    @Test fun titleValidationAndWhitespace() = runBlocking {
        repo.add("  保留长文本" + "字".repeat(180) + "  ")
        assertFalse(database.tasks().all().single().title.startsWith(" "))
        for (invalid in listOf(" ", "字".repeat(201))) {
            try { repo.add(invalid); fail("Expected invalid title rejection") } catch (_: IllegalArgumentException) {}
        }
    }
}
