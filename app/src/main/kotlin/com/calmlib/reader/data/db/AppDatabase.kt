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
    version = 6,
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

        /**
         * v5 → v6: bookmarks remember a character offset, which survives font
         * and margin changes where a page index doesn't. Existing bookmarks are
         * kept; the two kinds whose offset is certain are converted — the start
         * of a chapter, and a bookmark on the page the book is saved at (that
         * position's offset is stored on the book). The rest keep using their
         * page index.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN charOffset INTEGER NOT NULL DEFAULT -1")
                db.execSQL(
                    """
                    UPDATE bookmarks SET charOffset = 0
                    WHERE page = 0
                      AND bookId IN (SELECT id FROM books WHERE format != 'PDF')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE bookmarks SET charOffset = (
                        SELECT CAST(b.scrollOffset AS INTEGER) FROM books b WHERE b.id = bookmarks.bookId
                    )
                    WHERE charOffset = -1 AND EXISTS (
                        SELECT 1 FROM books b
                        WHERE b.id = bookmarks.bookId
                          AND b.format != 'PDF'
                          AND b.scrollOffset > 0
                          AND b.currentChapter = bookmarks.chapter
                          AND b.currentPage = bookmarks.page
                    )
                    """.trimIndent()
                )
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
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // NO fallbackToDestructiveMigration: a forgotten migration
                    // would silently delete every book, highlight and reading
                    // position the user has. Crashing loudly in development is
                    // the correct trade; users never lose their library.
                    .build()
                    .also { instance = it }
            }
    }
}
