package com.nedrichards.brooklet.wear

import com.nedrichards.brooklet.wear.data.WearEntrySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxRefreshStateTest {
    @Test fun `ripple carries its drawn pose through release spin and retry without restarting`() {
        val pose = RipplePose()
        val ready = pose.rotation(1f, armed = true, spinning = false, tick = 90f)
        assertEquals(ready, pose.rotation(1f, armed = false, spinning = false, tick = 0f), .001f)
        assertEquals(ready, pose.rotation(1f, armed = false, spinning = true, tick = 0f), .001f)
        val running = pose.rotation(1f, armed = false, spinning = true, tick = 180f)
        assertEquals(ready + 180f, running, .001f)
        assertEquals(running, pose.rotation(1f, armed = false, spinning = false, tick = 0f), .001f)
        assertEquals(running, pose.rotation(1f, armed = false, spinning = true, tick = 0f), .001f)
    }

    @Test fun `extra crown travel still tensions the pull beyond the threshold with more resistance`() {
        val before = crownPullOffset(CrownPull.THRESHOLD * .75f)
        val threshold = crownPullOffset(CrownPull.THRESHOLD)
        val beyond = crownPullOffset(CrownPull.THRESHOLD * 1.25f)
        assertTrue(before < threshold)
        assertTrue(threshold < beyond)
        assertTrue((beyond - threshold) < before / 3f)
        assertEquals(beyond, crownPullOffset(CrownPull.THRESHOLD * 10f))
    }

    @Test fun `explicit refresh holds remote removals until its completed snapshot`() {
        val initial = InboxArrival().receive(listOf(entry(3), entry(2)))
        val pulling = initial.receive(listOf(entry(4), entry(3, starred = true)), holdRemovals = true)
        assertEquals(listOf(3L, 2L), pulling.entries.map { it.id })
        assertTrue(pulling.entries.first().starred)
        assertTrue(pulling.newIds.isEmpty())
        val finished = pulling.completed(listOf(entry(4), entry(3, starred = true))).reveal()
        assertEquals(listOf(4L, 3L), finished.entries.map { it.id })
    }

    @Test fun `local read remains immediate while remote removals are held`() {
        val initial = InboxArrival().receive(listOf(entry(3), entry(2)))
        val read = initial.hide(3).receive(listOf(entry(4), entry(2)), holdRemovals = true)
        assertEquals(listOf(2L), read.entries.map { it.id })
        val undone = read.restore(3, 0).receive(listOf(entry(4), entry(3), entry(2)), holdRemovals = true)
        assertEquals(listOf(3L, 2L), undone.entries.map { it.id })
    }

    @Test fun `a deliberate burst at the top arms and reverse input cancels`() {
        val pull = CrownPull().rotate(-40f, true, 1_000).rotate(-40f, true, 1_100)
        assertTrue(pull.armed)
        assertFalse(pull.rotate(30f, true, 1_200).armed)
        assertEquals(0f, pull.rotate(100f, true, 1_200).distance)
    }

    @Test fun `a crown fling arriving at the top cannot arm until a fresh burst`() {
        val scrolling = CrownPull().rotate(-200f, false, 1_000)
        val boundary = scrolling.rotate(-200f, true, 1_100)
        assertEquals(0f, boundary.distance)
        assertFalse(boundary.armed)
        assertTrue(boundary.rotate(-80f, true, 1_400).armed)
    }

    @Test fun `leaving the top clears the pull and invalid deltas do nothing`() {
        val pull = CrownPull().rotate(-80f, true, 1_000)
        assertEquals(0f, pull.rotate(10f, false, 1_100).distance)
        assertEquals(pull, pull.rotate(Float.NaN, true, 1_100))
    }

    @Test fun `pages wait for completion while edits and removals remain live`() {
        val initial = InboxArrival().receive(listOf(entry(3), entry(2)))
        val firstPage = initial.receive(listOf(entry(5), entry(3, starred = true), entry(2)))
        val secondPage = firstPage.receive(listOf(entry(5), entry(4), entry(3, starred = true)))
        assertEquals(listOf(3L), secondPage.entries.map { it.id })
        assertTrue(secondPage.entries.single().starred)
        assertTrue(secondPage.newIds.isEmpty())
        val completed = secondPage.completed(secondPage.latest)
        assertEquals(setOf(4L, 5L), completed.newIds)
        assertEquals(listOf(5L, 4L, 3L), completed.reveal().entries.map { it.id })
    }

    @Test fun `equal count replacement is still an arrival and unchanged sync is quiet`() {
        val initial = InboxArrival().receive(listOf(entry(3), entry(2)))
        val completed = initial.completed(listOf(entry(4), entry(3)))
        assertEquals(setOf(4L), completed.newIds)
        val revealed = completed.reveal()
        assertTrue(revealed.completed(revealed.latest).newIds.isEmpty())
    }

    @Test fun `revealing completed arrivals excludes pages from a later unfinished pull`() {
        val first = InboxArrival().receive(listOf(entry(1))).completed(listOf(entry(2), entry(1)))
        val second = first.receive(listOf(entry(3), entry(2), entry(1)))
        assertEquals(setOf(2L), second.newIds)
        assertEquals(listOf(2L, 1L), second.reveal().entries.map { it.id })
    }

    @Test fun `undo restores locally without publishing pending arrivals`() {
        val initial = InboxArrival().receive(listOf(entry(2), entry(1)))
        val removed = initial.receive(listOf(entry(3), entry(1)))
        val restored = removed.restore(2, 0).receive(listOf(entry(3), entry(2), entry(1)))
        assertEquals(listOf(2L, 1L), restored.entries.map { it.id })
        assertTrue(restored.newIds.isEmpty())
    }

    @Test fun `initial cached content is a baseline rather than an arrival`() {
        val initial = InboxArrival().completed(listOf(entry(2), entry(1)))
        assertEquals(2, initial.entries.size)
        assertTrue(initial.newIds.isEmpty())
    }

    private fun entry(id: Long, starred: Boolean = false) = WearEntrySummary(
        id = id, feedTitle = "Feed", title = "Article $id", publishedAt = id,
        read = false, starred = starred, hasBody = true,
    )
}
