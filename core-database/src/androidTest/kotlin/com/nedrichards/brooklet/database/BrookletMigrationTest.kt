package com.nedrichards.brooklet.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class BrookletMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        requireNotNull(BrookletDatabase::class.java.canonicalName),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migratesVersion1ToVersion2() {
        helper.createDatabase("brooklet-migration-test", 1).close()
        helper.runMigrationsAndValidate(
            "brooklet-migration-test",
            2,
            true,
            BrookletDatabase.MIGRATION_1_2,
        ).close()
    }
}
