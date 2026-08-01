package com.calmlib.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookCollection
import com.calmlib.reader.data.model.Bookmark
import com.calmlib.reader.data.model.Collection
import com.calmlib.reader.data.model.Highlight
import com.calmlib.reader.data.model.ReadingSession
import com.calmlib.reader.data.model.VocabularyEntry

@Database(
    entities = [
        Book::class,
        Collection::class,
        BookCollection::class,
        Bookmark::class,
        Highlight::class,
        VocabularyEntry::class,
        ReadingSession::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun collectionDao(): CollectionDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun statsDao(): StatsDao

    companion object {
        /** v3 → v4: per-book settings column. Pure additive, no data loss. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readingSettingsJson TEXT")
            }
        }

        /** v4 → v5: whole-book progress fraction. Additive, no data loss. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN bookProgress REAL NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "calmlib.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
