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
            3,
            true,
            BrookletDatabase.MIGRATION_1_2,
            BrookletDatabase.MIGRATION_2_3,
        ).close()
    }

    @Test fun migratesVersion2ToCurrent() {
        helper.createDatabase("brooklet-migration-test-v2", 2).close()
        helper.runMigrationsAndValidate(
            "brooklet-migration-test-v2",
            3,
            true,
            BrookletDatabase.MIGRATION_2_3,
        ).close()
    }
}
