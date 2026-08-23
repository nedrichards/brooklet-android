package com.nedrichards.brooklet.baselineprofile

import android.graphics.Rect
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

internal const val PACKAGE_NAME = "com.nedrichards.brooklet"
private const val UI_TIMEOUT_MS = 15_000L
private val entryTagPattern = Pattern.compile("entry-[0-9]+")

internal fun MacrobenchmarkScope.startInbox(): UiObject2 {
    restartApp()
    repeat((UI_TIMEOUT_MS / 250).toInt()) {
        if (device.hasObject(By.res("reader-content"))) {
            device.pressBack()
        } else {
            val inboxLabels = device.findObjects(By.text("Inbox"))
            val list = device.findObject(By.res("entry-list"))
            // Inbox has both the top-bar title and bottom navigation label.
            if (list != null && inboxLabels.size >= 2) return list
            inboxLabels.lastOrNull()?.click()
        }
        SystemClock.sleep(250)
    }
    error("Timed out waiting for a populated Inbox")
}

internal fun MacrobenchmarkScope.startReadLibrary(): UiObject2 {
    startInbox()
    requireObject(By.text("Library"), "Library navigation").click()
    requireObject(By.text("Read"), "Read library scope").click()
    return requireObject(By.res("entry-list"), "the read-article list")
}

internal fun MacrobenchmarkScope.scrollEntryList(list: UiObject2, repetitions: Int = 3) {
    repeat(repetitions) { swipeContent(list, forward = true) }
    repeat(repetitions) { swipeContent(list, forward = false) }
}

internal fun MacrobenchmarkScope.firstVisibleEntry(): UiObject2 =
    device.findObjects(By.res(entryTagPattern))
        .filter { it.visibleBounds.height() > 0 }
        .minByOrNull { it.visibleBounds.top }
        ?: error("No visible article row")

internal fun MacrobenchmarkScope.openEntry(row: UiObject2): UiObject2 {
    row.click()
    return requireObject(By.res("reader-content"), "reader content")
}

internal fun MacrobenchmarkScope.scrollReader(reader: UiObject2, repetitions: Int = 3) {
    repeat(repetitions) { swipeContent(reader, forward = true) }
}

internal fun MacrobenchmarkScope.keepCurrentEntryUnread() {
    requireObject(By.desc("Keep unread"), "Keep unread").click()
    requireObject(By.res("entry-list"), "Inbox after Keep unread")
}

internal fun MacrobenchmarkScope.swipeAwayAndUndo(row: UiObject2) {
    val bounds = row.visibleBounds.insetForSwipe()
    device.swipe(bounds.right, bounds.centerY(), bounds.left, bounds.centerY(), 18)
    requireObject(By.text("Undo"), "Undo action").click()
    requireObject(By.res("entry-list"), "Inbox after Undo")
    SystemClock.sleep(500)
}

/**
 * Finds a real cached read article containing an image block. Read articles
 * avoid changing Miniflux state while the benchmark explores candidates.
 */
internal fun MacrobenchmarkScope.findImageEntryTag(maxEntries: Int = 20): String {
    val seen = linkedSetOf<String>()
    repeat(maxEntries) {
        val list = requireObject(By.res("entry-list"), "the read-article list")
        val next = device.findObjects(By.res(entryTagPattern))
            .filter { it.visibleBounds.height() > 0 }
            .sortedBy { it.visibleBounds.top }
            .firstOrNull { it.resourceName !in seen }
        if (next == null) {
            swipeContent(list, forward = true)
        } else {
            val tag = requireNotNull(next.resourceName)
            seen += tag
            val reader = openEntry(next)
            if (readerContainsImage(reader)) {
                device.pressBack()
                requireObject(By.res("entry-list"), "read list after image discovery")
                return tag
            }
            device.pressBack()
            requireObject(By.res("entry-list"), "read list after article inspection")
        }
    }
    error("No image-bearing article found in the first $maxEntries cached read entries")
}

private fun MacrobenchmarkScope.readerContainsImage(reader: UiObject2): Boolean {
    repeat(6) {
        if (device.wait(Until.hasObject(By.res("article-image")), 500)) return true
        swipeContent(reader, forward = true)
    }
    return device.wait(Until.hasObject(By.res("article-image")), 500)
}

internal fun MacrobenchmarkScope.scrollToEntry(tag: String): UiObject2 {
    repeat(20) {
        device.findObject(By.res(tag))?.let { return it }
        val list = requireObject(By.res("entry-list"), "the read-article list")
        swipeContent(list, forward = true)
    }
    error("Could not find $tag")
}

internal fun MacrobenchmarkScope.requireImageBlock() {
    check(device.wait(Until.hasObject(By.res("article-image")), UI_TIMEOUT_MS)) {
        "Selected reader did not expose an article image"
    }
}

private fun MacrobenchmarkScope.restartApp() {
    device.executeShellCommand("am force-stop $PACKAGE_NAME")
    pressHome()
    startActivityAndWait()
}

private fun MacrobenchmarkScope.requireObject(selector: androidx.test.uiautomator.BySelector, label: String): UiObject2 =
    device.wait(Until.findObject(selector), UI_TIMEOUT_MS) ?: error("Timed out waiting for $label")

private fun MacrobenchmarkScope.swipeContent(container: UiObject2, forward: Boolean) {
    val bounds = container.visibleBounds
    val inset = bounds.height() / 5
    val startY = if (forward) bounds.bottom - inset else bounds.top + inset
    val endY = if (forward) bounds.top + inset else bounds.bottom - inset
    device.swipe(bounds.centerX(), startY, bounds.centerX(), endY, 20)
    SystemClock.sleep(250)
}

private fun Rect.insetForSwipe(): Rect = Rect(
    left + width() / 8,
    top,
    right - width() / 8,
    bottom,
)
