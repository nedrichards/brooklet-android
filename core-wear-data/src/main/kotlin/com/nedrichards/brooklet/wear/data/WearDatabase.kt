package com.nedrichards.brooklet.wear.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WearAccountEntity::class,
        WearEntryEntity::class,
        WearMutationEntity::class,
        WearKarakeepEntity::class,
        WearSyncStateEntity::class,
        WearReaderPositionEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class WearDatabase : RoomDatabase() {
    abstract fun dao(): WearDao

    companion object {
        @Volatile private var instance: WearDatabase? = null

        fun getInstance(context: Context): WearDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WearDatabase::class.java,
                "brooklet-watch.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS wear_reader_positions (
                        entryId INTEGER NOT NULL,
                        anchorItemIndex INTEGER NOT NULL,
                        anchorItemScrollOffset INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(entryId)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
