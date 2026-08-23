package com.nedrichards.brooklet.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
        filterPredicate = ::isBrookletRule,
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = false,
        filterPredicate = ::isBrookletRule,
    ) {
        val inbox = startInbox()
        scrollEntryList(inbox)
        swipeAwayAndUndo(firstVisibleEntry())

        val reader = openEntry(firstVisibleEntry())
        scrollReader(reader)
        keepCurrentEntryUnread()

        startReadLibrary()
        val imageEntryTag = findImageEntryTag()
        val imageReader = openEntry(scrollToEntry(imageEntryTag))
        requireImageBlock()
        scrollReader(imageReader, repetitions = 4)
    }
}

private fun isBrookletRule(rule: String): Boolean =
    "Lcom/nedrichards/brooklet/" in rule
