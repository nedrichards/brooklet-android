package com.nedrichards.brooklet.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class BrookletMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BrookletDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migratesVersion1ToCurrent() {
        helper.createDatabase("brooklet-migration-test", 1).close()
        helper.runMigrationsAndValidate(
            "brooklet-migration-test",
            4,
            true,
            BrookletDatabase.MIGRATION_1_2,
            BrookletDatabase.MIGRATION_2_3,
            BrookletDatabase.MIGRATION_3_4,
        ).close()
    }

    @Test fun migratesVersion2ToCurrent() {
        helper.createDatabase("brooklet-migration-test-v2", 2).close()
        helper.runMigrationsAndValidate(
            "brooklet-migration-test-v2",
            4,
            true,
            BrookletDatabase.MIGRATION_2_3,
            BrookletDatabase.MIGRATION_3_4,
        ).close()
    }
    @Test fun migratesVersion3RetainingBodiesAndMarksThemForPreparation() {
        helper.createDatabase("brooklet-search-migration-test", 3).apply {
            execSQL("""INSERT INTO entries (accountId, id, feedId, title, url, author,
                publishedAt, changedAt, html, parsedBlocksJson, read, starred, readingMinutes, lastOpenedAt)
                VALUES (1, 7, 1, 'Old article', 'https://example.com/7', NULL,
                    1, 1, '<p>Heat pump</p>', '[]', 1, 0, 1, NULL)""")
            close()
        }
        helper.runMigrationsAndValidate("brooklet-search-migration-test", 4, true,
            BrookletDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT html, searchVersion, searchBody FROM entries WHERE id = 7").use { cursor ->
                org.junit.Assert.assertTrue(cursor.moveToFirst())
                org.junit.Assert.assertEquals("<p>Heat pump</p>", cursor.getString(0))
                org.junit.Assert.assertEquals(0, cursor.getInt(1))
                org.junit.Assert.assertEquals("", cursor.getString(2))
            }
        }
    }

}
