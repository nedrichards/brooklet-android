package com.nedrichards.brooklet.wear.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        WearDatabase::class.java,
    )

    @Test fun migrationFromOneAddsReaderPositionsWithoutLosingCachedEntries() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO wear_entries (
                    id, feedId, feedTitle, title, url, author, publishedAt, changedAt,
                    blocksJson, bodyBytes, bodyTruncated, bodyLastAccessedAt, read,
                    starred, readingMinutes, lastOpenedAt
                ) VALUES (7, 1, 'Feed', 'Title', 'https://example.com/7', NULL, 1, 1,
                    '', 0, 0, NULL, 0, 0, 1, NULL)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 2, true, WearDatabase.MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM wear_entries WHERE id = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM wear_reader_positions").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "wear-migration-test"
    }
}
