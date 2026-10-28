/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frankegan.sqlkite

import android.database.Cursor
import android.util.Log
import androidx.annotation.CheckResult
import androidx.annotation.WorkerThread
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executor

/**
 * A lightweight wrapper around [SupportSQLiteOpenHelper] which allows for continuously
 * observing the result of a query.
 */
class SqlKite internal constructor(
    private val logger: Logger,
    private val queryTransformer: (Flow<Query>) -> Flow<Query>
) {

    class Builder {

        private var logger = DEFAULT_LOGGER
        private var queryTransformer = DEFAULT_TRANSFORMER

        @CheckResult
        fun logger(logger: Logger): Builder {
            this.logger = logger
            return this
        }

        @CheckResult
        fun queryTransformer(queryTransformer: (Flow<Query>) -> Flow<Query>): Builder {
            this.queryTransformer = queryTransformer
            return this
        }

        @CheckResult
        fun build(): SqlKite {
            return SqlKite(logger, queryTransformer)
        }
    }

    /**
     * Wrap a [SupportSQLiteOpenHelper] for observable queries.
     *
     *
     * While not strictly required, instances of this class assume that they will be the only ones
     * interacting with the underlying [SupportSQLiteOpenHelper] and it is required for
     * automatic notifications of table changes to work. See [the][KiteDatabase.createQuery] for more information on that behavior.
     *
     * @param dispatcher The [CoroutineDispatcher] on which items from [KiteDatabase.createQuery]
     * will be emitted, and on which [mapToOne], [mapToOneOrDefault], [mapToList] and [asRows] run
     * those queries and read their cursors.
     */
    @CheckResult
    fun wrapDatabaseHelper(
        helper: SupportSQLiteOpenHelper,
        dispatcher: CoroutineDispatcher,
        transactionExecutor: Executor? = null
    ): KiteDatabase {
        return if (transactionExecutor == null) {
            KiteDatabase(helper, logger, dispatcher, queryTransformer = queryTransformer)
        } else {
            KiteDatabase(helper, logger, dispatcher, transactionExecutor, queryTransformer)
        }
    }

    /** An executable query.  */
    fun interface Query {

        /**
         * Execute the query on the underlying database and return the resulting cursor.
         *
         * @return A [Cursor] with query results, or `null` when the query could not be
         * executed due to a problem with the underlying store. Unfortunately it is not well documented
         * when `null` is returned. It usually involves a problem in communicating with the
         * underlying store and should either be treated as failure or ignored for retry at a later
         * time.
         */
        @CheckResult
        @WorkerThread
        suspend fun run(): Cursor?

        /**
        +         * Where [mapToOne], [mapToOneOrDefault], [mapToList] and [asRows] call [run] and read the
        +         * cursor. `null` leaves that work on the collector's dispatcher.
        +         */
        val dispatcher: CoroutineDispatcher?
            get() = null
    }

    /** A simple indirection for logging debug messages.  */
    fun interface Logger {

        fun log(message: String)
    }

    companion object {

        val DEFAULT_LOGGER: Logger = Logger { message -> Log.d("SqlKite", message) }

        val DEFAULT_TRANSFORMER: (Flow<Query>) -> Flow<Query> = { queryFlow -> queryFlow }
    }
}