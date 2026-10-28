package com.frankegan.sqlkite

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

class QueryDispatcherTest {

    private lateinit var queryDispatcher: ExecutorCoroutineDispatcher
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: KiteDatabase

    @Before
    fun setUp() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .callback(TestDb())
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        queryDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, QUERY_THREAD) }
            .asCoroutineDispatcher()
        db = SqlKite.Builder().build().wrapDatabaseHelper(helper, queryDispatcher)
    }

    @After
    fun tearDown() {
        queryDispatcher.close()
    }

    @Test
    fun mapToOneReadsOnQueryDispatcher() = runBlocking<Unit> {
        val thread = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOne { currentThreadName() }
            .first()
        Truth.assertThat(thread).isEqualTo(QUERY_THREAD)
    }

    @Test
    fun mapToOneOrDefaultReadsOnQueryDispatcher() = runBlocking<Unit> {
        val thread = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOneOrDefault("default") { currentThreadName() }
            .first()
        Truth.assertThat(thread).isEqualTo(QUERY_THREAD)
    }

    @Test
    fun mapToListReadsOnQueryDispatcher() = runBlocking<Unit> {
        val threads = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .mapToList { currentThreadName() }
            .first()
        Truth.assertThat(threads).containsExactly(QUERY_THREAD, QUERY_THREAD, QUERY_THREAD)
    }

    @Test
    fun asRowsReadsOnQueryDispatcher() = runBlocking<Unit> {
        val threads = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .first()
            .asRows { currentThreadName() }
            .toList()
        Truth.assertThat(threads).containsExactly(QUERY_THREAD, QUERY_THREAD, QUERY_THREAD)
    }

    @Test(timeout = 5_000)
    fun mapToListThrowsWhenCollectorHoldsTransaction() {
        val employees = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .mapToList { cursor -> cursor.getString(0) }
        helper.writableDatabase.beginTransaction()
        try {
            runBlocking { employees.first() }
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo("Cannot execute observable query in a transaction.")
        } finally {
            helper.writableDatabase.endTransaction()
        }
    }

    @Test(timeout = 5_000)
    fun asRowsThrowsWhenCollectorHoldsTransaction() {
        val query = runBlocking { db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).first() }
        helper.writableDatabase.beginTransaction()
        try {
            runBlocking { query.asRows { cursor -> cursor.getString(0) }.toList() }
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e).hasMessageThat().isEqualTo("Cannot execute observable query in a transaction.")
        } finally {
            helper.writableDatabase.endTransaction()
        }
    }

    private fun currentThreadName(): String = Thread.currentThread().name

    private companion object {
        const val QUERY_THREAD = "sqlkite-query"
    }
}