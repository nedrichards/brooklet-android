package com.nedrichards.brooklet

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nedrichards.brooklet.database.ReaderPositionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReaderPositionJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkpointsDoNotRestoreTheReaderAgain() {
        val list = LazyListState()
        var stored by mutableStateOf(ReaderPositionEntity(1, 11, 15, 0, 1))
        var loads = 0
        val saves = mutableListOf<Pair<Int, Int>>()
        compose.setContent {
            val position = stored
            PersistReaderPosition(11, 11, list, loadPosition = { loads++; position }) { block, offset ->
                saves += block to offset
                stored = position.copy(firstVisibleBlock = block, offsetPx = offset, updatedAt = position.updatedAt + 1)
            }
            LazyColumn(state = list) { items(100) { Text("Paragraph $it") } }
        }
        compose.waitUntil(5_000) { list.firstVisibleItemIndex == 15 }

        compose.runOnIdle { runBlocking { list.scrollToItem(25) } }
        compose.waitUntil(5_000) { saves.any { it.first == 25 } }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(25, list.firstVisibleItemIndex)
            assertEquals(1, loads)
            assertEquals(1, saves.size)
        }
    }

    @Test fun switchingArticlesFlushesThePreviousArticleToItsOwnCallback() {
        var articleId by mutableStateOf(11L)
        var activeList: LazyListState? = null
        val saves = mutableListOf<Pair<Long, Int>>()
        compose.setContent {
            val id = articleId
            val list = remember(id) { LazyListState() }
            activeList = list
            PersistReaderPosition(id, id, list, loadPosition = { ReaderPositionEntity(1, id, 5, 0, 1) }) { block, _ ->
                saves += id to block
            }
            LazyColumn(state = list) { items(100) { Text("Article $id paragraph $it") } }
        }
        compose.waitUntil(5_000) { activeList?.firstVisibleItemIndex == 5 }
        compose.runOnIdle {
            runBlocking { activeList!!.scrollToItem(25) }
            articleId = 12
        }
        compose.waitUntil(5_000) { activeList?.firstVisibleItemIndex == 5 && saves.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(11L to 25), saves) }
    }

    @Test fun stoppingTheAppFlushesBeforeTheScrollDebounce() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val list = LazyListState()
        var loads = 0
        val saves = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PersistReaderPosition(11, 11, list, loadPosition = { loads++; null }) { block, _ -> saves += block }
                LazyColumn(state = list) { items(100) { Text("Paragraph $it") } }
            }
        }
        compose.waitUntil(5_000) { loads == 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            runBlocking { list.scrollToItem(25) }
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals(listOf(25), saves)
        }
    }
}
