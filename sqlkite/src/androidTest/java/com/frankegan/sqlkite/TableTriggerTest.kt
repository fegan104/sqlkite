package com.frankegan.sqlkite

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs queries on a real thread pool. [KiteDatabaseTest]'s test dispatcher handles each trigger before the next is
 * sent, so triggers never arrive faster than a query can take them there.
 */
class TableTriggerTest {

    private lateinit var db: KiteDatabase

    @Before
    fun setUp() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .callback(TestDb())
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        db = SqlKite.Builder().build().wrapDatabaseHelper(helper, Dispatchers.IO)
    }

    @Test
    fun queryReRunsForTheSecondOfTwoBackToBackTriggers() = runBlocking {
        repeat(BACK_TO_BACK_ATTEMPTS) {
            db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST).test(timeout = 1.seconds) {
                awaitItem()
                delay(50.milliseconds)

                db.sendTableTrigger(TestDb.TABLE_EMPLOYEE)
                db.sendTableTrigger(TestDb.TABLE_MANAGER)

                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun queryDeliversTriggerSentFromItsFirstEmission() = runBlocking {
        repeat(FIRST_EMISSION_ATTEMPTS) { attempt ->
            val emissions = withTimeoutOrNull(1_000) {
                db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
                    .withIndex()
                    .onEach { (index, _) -> if (index == 0) db.sendTableTrigger(TestDb.TABLE_EMPLOYEE) }
                    .take(2)
                    .count()
            }
            Truth.assertWithMessage("attempt $attempt").that(emissions).isEqualTo(2)
        }
    }

    companion object {
        private const val BACK_TO_BACK_ATTEMPTS = 20
        private const val FIRST_EMISSION_ATTEMPTS = 500
    }
}
