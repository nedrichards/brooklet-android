package com.nedrichards.brooklet.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupWithoutCompilation() = startup(CompilationMode.None())

    @Test
    fun coldStartupWithBaselineProfile() = startup(CompilationMode.Partial())

    @Test
    fun inboxScrollFrames() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startInbox()
        },
    ) {
        scrollEntryList(requireNotNull(device.findObject(androidx.test.uiautomator.By.res("entry-list"))), repetitions = 4)
    }

    @Test
    fun readerOpenFrames() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = { startInbox() },
    ) {
        openEntry(firstVisibleEntry())
        keepCurrentEntryUnread()
    }

    @Test
    fun swipeRemovalAndUndoFrames() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = { startInbox() },
    ) {
        swipeAwayAndUndo(firstVisibleEntry())
    }

    @Test
    fun imageHeavyReaderFrames() {
        var imageEntryTag: String? = null
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            iterations = 5,
            setupBlock = {
                startReadLibrary()
                if (imageEntryTag == null) imageEntryTag = findImageEntryTag()
                device.executeShellCommand("am force-stop $PACKAGE_NAME")
                startReadLibrary()
                scrollToEntry(requireNotNull(imageEntryTag))
            },
        ) {
            val reader = openEntry(requireNotNull(device.findObject(androidx.test.uiautomator.By.res(requireNotNull(imageEntryTag)))))
            requireImageBlock()
            scrollReader(reader, repetitions = 4)
        }
    }

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
