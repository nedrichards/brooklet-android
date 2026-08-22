package com.nedrichards.brooklet.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, FeedEntity::class, EntryEntity::class,
        EnclosureEntity::class, ReaderPositionEntity::class, SyncCursorEntity::class,
        PendingMutationEntity::class, PendingKarakeepEntity::class, KarakeepConfigEntity::class,
        StoragePolicyEntity::class, SyncStateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class BrookletDatabase : RoomDatabase() {
    abstract fun dao(): BrookletDao
    companion object {
        fun create(context: Context): BrookletDatabase = Room.databaseBuilder(
            context.applicationContext,
            BrookletDatabase::class.java,
            "brooklet.db",
        ).addMigrations(MIGRATION_1_2)
            // The UI and WorkManager each hold a Room instance. Propagate commits
            // between them so initial sync can yield to the inbox on its first page.
            .enableMultiInstanceInvalidation()
            .build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        accountId INTEGER NOT NULL PRIMARY KEY,
                        phase TEXT NOT NULL,
                        processed INTEGER NOT NULL,
                        total INTEGER NOT NULL,
                        error TEXT,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
    }
}
