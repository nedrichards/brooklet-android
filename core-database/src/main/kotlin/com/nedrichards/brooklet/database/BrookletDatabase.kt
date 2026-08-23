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
    version = 3,
    exportSchema = true,
)
abstract class BrookletDatabase : RoomDatabase() {
    abstract fun dao(): BrookletDao
    companion object {
        @Volatile private var instance: BrookletDatabase? = null

        fun getInstance(context: Context): BrookletDatabase = instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }

        fun create(context: Context): BrookletDatabase = Room.databaseBuilder(
            context.applicationContext,
            BrookletDatabase::class.java,
            "brooklet.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

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

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_karakeep` ADD COLUMN `completedAt` INTEGER DEFAULT NULL")
                listOf(
                    "index_feeds_accountId",
                    "index_feeds_categoryId",
                    "index_entries_accountId",
                    "index_entries_feedId",
                    "index_entries_read",
                    "index_entries_starred",
                    "index_entries_changedAt",
                    "index_pending_mutations_createdAt",
                ).forEach { name -> db.execSQL("DROP INDEX IF EXISTS `$name`") }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feeds_accountId_categoryId` ON `feeds` (`accountId`, `categoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_accountId_publishedAt` ON `entries` (`accountId`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_accountId_read_publishedAt` ON `entries` (`accountId`, `read`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_accountId_starred_publishedAt` ON `entries` (`accountId`, `starred`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_entries_accountId_feedId_publishedAt` ON `entries` (`accountId`, `feedId`, `publishedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_mutations_accountId_createdAt` ON `pending_mutations` (`accountId`, `createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_karakeep_accountId_state_createdAt` ON `pending_karakeep` (`accountId`, `state`, `createdAt`)")
            }
        }
    }
}
