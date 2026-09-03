package com.nedrichards.brooklet

import com.nedrichards.brooklet.sync.SyncActivity
import com.nedrichards.brooklet.sync.SyncActivityState
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDiagnosticsTest {
    @Test fun completedWorkerDoesNotClaimRemoteDataIsUpToDate() {
        assertEquals(
            "Completed",
            syncSummary(SyncActivity(), "COMPLETE", processed = 0, total = 0, error = null),
        )
    }

    @Test fun runningEntryPullReportsProgress() {
        assertEquals(
            "Caching 100 of 141 articles",
            syncSummary(
                SyncActivity(state = SyncActivityState.RUNNING),
                "PULLING_ENTRIES",
                processed = 100,
                total = 141,
                error = null,
            ),
        )
    }

    @Test fun diagnosticTimestampDistinguishesMissingAndRecordedTimes() {
        assertEquals("Never", diagnosticTimestamp(null))
        assertEquals(
            "1 Jan 2026, 12:30",
            diagnosticTimestamp(
                epochMillis = Instant.parse("2026-01-01T12:30:00Z").toEpochMilli(),
                zoneId = ZoneOffset.UTC,
                locale = Locale.UK,
            ),
        )
    }
}
