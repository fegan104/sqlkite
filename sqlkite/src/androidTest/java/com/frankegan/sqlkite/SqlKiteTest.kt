package com.frankegan.sqlkite

import android.database.Cursor
import android.database.MatrixCursor
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import com.google.common.truth.Truth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4ClassRunner::class)
class SqlKiteTest {

    @Test
    fun asRowsEmpty() = runTest {
        val cursor = MatrixCursor(COLUMN_NAMES)
        val query: SqlKite.Query = CursorQuery(cursor)
        val names: List<Name?> = query.asRows(Name.MAP).toList()
        Truth.assertThat(names).isEmpty()
    }

    @Test
    fun asRows() = runTest {
        val cursor = MatrixCursor(COLUMN_NAMES)
        cursor.addRow(arrayOf<Any>("Alice", "Allison"))
        cursor.addRow(arrayOf<Any>("Bob", "Bobberson"))
        val query: SqlKite.Query = CursorQuery(cursor)
        val names: List<Name?> = query.asRows(Name.MAP).toList()
        Truth.assertThat(names).containsExactly(Name("Alice", "Allison"), Name("Bob", "Bobberson"))
    }

    @Test
    fun asRowsStopsWhenUnsubscribed() = runTest {
        val cursor = MatrixCursor(COLUMN_NAMES)
        cursor.addRow(arrayOf<Any>("Alice", "Allison"))
        cursor.addRow(arrayOf<Any>("Bob", "Bobberson"))
        val query: SqlKite.Query = CursorQuery(cursor)
        val count = AtomicInteger()
        query.asRows { cursor ->
            count.incrementAndGet()
            Name.MAP(cursor)
        }.first()
        Truth.assertThat(count.get()).isEqualTo(1)
    }

    internal class Name(val first: String, val last: String) {

        override fun equals(o: Any?): Boolean {
            if (o === this) return true
            if (o !is Name) return false
            val other = o
            return first == other.first && last == other.last
        }

        override fun hashCode(): Int {
            return first.hashCode() * 17 + last.hashCode()
        }

        override fun toString(): String {
            return "Name[$first $last]"
        }

        companion object {

            val MAP = { cursor: Cursor ->
                Name( //
                    cursor.getString(cursor.getColumnIndexOrThrow(FIRST_NAME)),
                    cursor.getString(cursor.getColumnIndexOrThrow(LAST_NAME))
                )
            }
        }
    }

    internal class CursorQuery(private val cursor: Cursor) : SqlKite.Query {

        override suspend fun run(): Cursor {
            return cursor
        }
    }

    companion object {

        private const val FIRST_NAME = "first_name"
        private const val LAST_NAME = "last_name"
        private val COLUMN_NAMES = arrayOf(FIRST_NAME, LAST_NAME)
    }
}