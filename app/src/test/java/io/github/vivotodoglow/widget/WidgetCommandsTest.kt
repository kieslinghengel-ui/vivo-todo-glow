package io.github.vivotodoglow.widget

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.vivotodoglow.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WidgetCommandsTest {
    private lateinit var database: TaskDatabase
    private lateinit var repo: TaskRepository
    private var now = 1_000_000L
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TaskDatabase::class.java).allowMainThreadQueries().build()
        repo = TaskRepository(database) { now }
    }
    @After fun close() { database.close() }
    @Test fun desktopCompletionHidesImmediatelyAndUndoWorksUntil4999ms() = runBlocking {
        val ids = (1..11).map { repo.add("任务$it") }
        WidgetCommands.execute(repo, "complete", ids[0])
        WidgetCommands.execute(repo, "complete", ids[0])
        assertEquals(ids[10], TaskPolicy.panel(repo.tasks.first()).last().id)
        assertEquals(1, TaskPolicy.pending(repo.tasks.first(), now).size)
        now += 4999
        assertTrue(repo.undo(ids[0]))
        assertEquals(ids[0], TaskPolicy.panel(repo.tasks.first()).first().id)
    }
    @Test fun independentCompletionsExpireSeparately() = runBlocking {
        val a = repo.add("A"); val b = repo.add("B")
        WidgetCommands.execute(repo, "complete", a)
        now += 1000
        WidgetCommands.execute(repo, "complete", b)
        now += 4000
        assertFalse(repo.undo(a))
        assertTrue(repo.undo(b))
        assertEquals(listOf("B"), TaskPolicy.active(repo.tasks.first()).map { it.title })
    }
    @Test fun dailyCompletionReturnsAtFifthAndCanBeUndoneOnDesktop() = runBlocking {
        val daily = repo.add("每日", true)
        repeat(5) { repo.add("任务$it") }
        WidgetCommands.execute(repo, "complete", daily)
        now += 800; repo.cleanup()
        assertEquals(daily, TaskPolicy.active(repo.tasks.first())[4].id)
        val event = TaskPolicy.pending(repo.tasks.first(), now).single()
        assertTrue(repo.undo(event.id))
        assertEquals(daily, TaskPolicy.active(repo.tasks.first()).first().id)
    }
    @Test fun arrowCommandsPersistOrderingAndIgnoreInvalidActions() = runBlocking {
        val a = repo.add("A"); val b = repo.add("B"); val c = repo.add("C")
        WidgetCommands.execute(repo, "down", a)
        assertEquals(listOf(b, a, c), TaskPolicy.active(repo.tasks.first()).map { it.id })
        WidgetCommands.execute(repo, "up", c)
        WidgetCommands.execute(repo, "unknown", a)
        WidgetCommands.execute(repo, "complete", -1)
        assertEquals(listOf(b, c, a), TaskPolicy.active(repo.tasks.first()).map { it.id })
    }
}
