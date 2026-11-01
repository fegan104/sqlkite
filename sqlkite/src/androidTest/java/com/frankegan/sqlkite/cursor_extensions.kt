package com.frankegan.sqlkite

import android.database.Cursor
import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth
import java.lang.AssertionError
import java.lang.StringBuilder

fun Cursor.hasRow(vararg values: Any?): Cursor {
    var row = 0
    Truth.assertThat(moveToNext()).named("row " + (row + 1) + " exists").isTrue()
    row += 1
    Truth.assertThat(columnCount).named("column count").isEqualTo(values.size)
    for (i in values.indices) {
        Truth.assertThat(getString(i))
            .named("row " + row + " column '" + getColumnName(i) + "'")
            .isEqualTo(values[i])
    }
    return this
}

fun Cursor.isExhausted() {
    if (moveToNext()) {
        val data = StringBuilder()
        for (i in 0 until columnCount) {
            if (i > 0) data.append(", ")
            data.append(getString(i))
        }
        throw AssertionError("Expected no more rows but was: $data")
    }
    close()
}

suspend fun <Q: SqlKite.Query> TurbineTestContext<Q>.awaitItemAndRunQuery(): Cursor {
    return awaitItem().run()!!
}