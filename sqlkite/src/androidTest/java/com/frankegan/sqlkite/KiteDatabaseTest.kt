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

import android.annotation.TargetApi
import android.content.ContentValues
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteTransactionListener
import android.os.Build
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.frankegan.sqlkite.TestDb.EmployeeTable
import com.google.common.truth.Truth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.lang.Exception
import java.util.ArrayList
import java.util.Collections


@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4ClassRunner::class)
class KiteDatabaseTest {

    private val testDb = TestDb()
    private val logs: MutableList<String?> = ArrayList()
    private val dispatcher = StandardTestDispatcher()
    private var killSwitch: Boolean = true

    @get:Rule
    val dbFolder: TemporaryFolder = TemporaryFolder()
    private lateinit var real: SupportSQLiteDatabase
    private lateinit var db: KiteDatabase

    @Before
    @Throws(IOException::class)
    fun setUp() {
        killSwitch = true
        val configuration = SupportSQLiteOpenHelper.Configuration
            .builder(ApplicationProvider.getApplicationContext())
            .callback(testDb)
            .name(dbFolder.newFile().path)
            .build()
        val factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
        val helper: SupportSQLiteOpenHelper = factory.create(configuration)
        real = helper.writableDatabase
        val logger: SqlKite.Logger = SqlKite.Logger { message ->
            logs.add(message)
        }

        db = KiteDatabase(helper, logger, dispatcher) { upstream ->
            upstream.takeWhile { killSwitch }
        }
    }

    fun runBoundTest(testBody: suspend TestScope.() -> Unit) = runTest(dispatcher, testBody = testBody)

    @Test
    fun loggerEnabled() = runBoundTest {
        db.setLoggingEnabled(true)
        db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
        Truth.assertThat(logs).isNotEmpty()
    }

    @Test
    fun loggerDisabled() = runBoundTest {
        db.setLoggingEnabled(false)
        db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
        Truth.assertThat(logs).isEmpty()
    }

    @Test
    fun loggerIndentsSqlForCreateQuery() = runBoundTest {
        db.setLoggingEnabled(true)
        db.createQuery(TestDb.TABLE_EMPLOYEE, "SELECT\n1").test {
            awaitItemAndRunQuery().close()
            Truth.assertThat(logs).containsExactly(
                """QUERY
  tables: [employee]
  sql: SELECT
       1"""
            )
        }
    }

    @Test
    fun loggerIndentsSqlForQuery() = runBoundTest {
        db.setLoggingEnabled(true)
        db.query("SELECT\n1").close()
        Truth.assertThat(logs).containsExactly(
            """QUERY
  sql: SELECT
       1
  args: []"""
        )
    }

    @Test
    fun loggerIndentsSqlForExecute() = runBoundTest {
        db.setLoggingEnabled(true)
        db.execute("PRAGMA\ncompile_options")
        Truth.assertThat(logs).containsExactly(
            """EXECUTE
  sql: PRAGMA
       compile_options"""
        )
    }

    @Test
    fun loggerIndentsSqlForExecuteWithArgs() = runBoundTest {
        db.setLoggingEnabled(true)
        db.execute("PRAGMA\ncompile_options", *arrayOfNulls<Any>(0))
        Truth.assertThat(logs).containsExactly(
            """EXECUTE
  sql: PRAGMA
       compile_options
  args: []"""
        )
    }

    @Test
    fun closePropagates() {
        db.close()
        Truth.assertThat(real.isOpen).isFalse()
    }

    @Test
    fun query() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryWithQueryObject() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES)).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryMapToList() = runBoundTest {
        val employees: List<TestDb.Employee?> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .mapToList(TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).containsExactly(
            TestDb.Employee("alice", "Alice Allison"),
            TestDb.Employee("bob", "Bob Bobberson"),
            TestDb.Employee("eve", "Eve Evenson")
        )
    }

    @Test
    fun queryMapToOne() = runBoundTest {
        val employees: TestDb.Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOne(TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
    }

    @Test
    fun queryMapToOneOrDefault() = runBoundTest {
        val employees: TestDb.Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOneOrDefault(TestDb.Employee("wrong", "Wrong Person"), TestDb.Employee.MAPPER)
            .first()
        Truth.assertThat(employees).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
    }

    @Test
    fun badQueryCallsError() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, "SELECT * FROM missing")
            .map { query -> query.run() }
            .test {
                Truth.assertThat(awaitError()).hasMessageThat().contains("no such table: missing")
            }
    }

    @Test
    fun queryWithArgs() = runBoundTest {
        db.createQuery(
            TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " WHERE " + TestDb.EmployeeTable.USERNAME + " = ?", "bob"
        ).test {
            awaitItemAndRunQuery()
                .hasRow("bob", "Bob Bobberson")
                .isExhausted()
        }
    }

    @Test
    fun queryObservesInsert() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .isExhausted()
        }

    }

    @Test
    fun queryInitialValueAndTriggerUsesScheduler() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))

            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenInsertFails() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_IGNORE, TestDb.employee("bob", "Bob Bobberson"))
        }
    }

    @Test
    fun queryNotNotifiedWhenQueryTransformerUnsubscribes() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            killSwitch = false
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitComplete()
        }
    }

    @Test
    fun queryObservesUpdate() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "Robert Bobberson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'bob'")
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Robert Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenUpdateAffectsZeroRows() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "John Johnson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'john'")
        }
    }

    @Test
    fun queryObservesDelete() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.delete(TestDb.TABLE_EMPLOYEE, TestDb.EmployeeTable.USERNAME + " = 'bob'")
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedWhenDeleteAffectsZeroRows() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.delete(TestDb.TABLE_EMPLOYEE, TestDb.EmployeeTable.USERNAME + " = 'john'")
        }
    }

    @Test
    fun queryMultipleTables() = runBoundTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesWithQueryObject() = runBoundTest {
        db.createQuery(TestDb.BOTH_TABLES, SimpleSQLiteQuery(TestDb.SELECT_MANAGER_LIST)).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesObservesChanges() = runBoundTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            // A new employee triggers, despite the fact that it's not in our result set.
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            // A new manager also triggers and it is in our result set.
            db.insert(TestDb.TABLE_MANAGER, SQLiteDatabase.CONFLICT_NONE, TestDb.manager(testDb.bobId, testDb.eveId))
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .hasRow("Bob Bobberson", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun queryMultipleTablesObservesChangesOnlyOnce() = runBoundTest {
        // Employee table is in this list twice. We should still only be notified once for a change.
        val tables = listOf(TestDb.TABLE_EMPLOYEE, TestDb.TABLE_MANAGER, TestDb.TABLE_EMPLOYEE)
        db.createQuery(tables, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            val values = ContentValues()
            values.put(TestDb.EmployeeTable.NAME, "Even Evenson")
            db.update(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, values, TestDb.EmployeeTable.USERNAME + " = 'eve'")
            awaitItemAndRunQuery()
                .hasRow("Even Evenson", "Alice Allison")
                .isExhausted()
        }
    }

    @Test
    fun queryNotNotifiedAfterDispose() = runBoundTest {
        coroutineScope {
            val queryCollector = launch {
                db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
                    awaitItemAndRunQuery()
                        .hasRow("alice", "Alice Allison")
                        .hasRow("bob", "Bob Bobberson")
                        .hasRow("eve", "Eve Evenson")
                        .isExhausted()
                }
            }
            queryCollector.cancel()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
        }
    }

    @Test
    fun executeSqlNoTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                db.execute("UPDATE ${TestDb.TABLE_EMPLOYEE} SET ${TestDb.EmployeeTable.NAME} = 'Zach'")
            }
    }

    @Test
    fun executeSqlWithArgsNoTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                db.execute("UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach")
            }
    }

    @Test
    fun executeSqlAndTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @Test
    fun executeSqlAndTriggerMultipleTables() = runBoundTest {
        turbineScope {
            var managerFlow: Flow<SqlKite.Query>? = null
            var employeeFlow: Flow<SqlKite.Query>? = null
            val managerJob = launch {
                managerFlow = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
                managerFlow.test {
                    awaitItemAndRunQuery()
                        .hasRow("Eve Evenson", "Alice Allison")
                        .isExhausted()
                }
            }
            val employeeJob = launch {
                employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
                employeeFlow.test {
                    awaitItemAndRunQuery()
                        .hasRow("alice", "Alice Allison")
                        .hasRow("bob", "Bob Bobberson")
                        .hasRow("eve", "Eve Evenson")
                        .isExhausted()
                }
            }

            joinAll(managerJob, employeeJob)

            db.executeAndTrigger(
                TestDb.BOTH_TABLES.toSet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + EmployeeTable.NAME + " = 'Zach'"
            )
            managerFlow!!.test {
                awaitItemAndRunQuery()
                    .hasRow("Zach", "Zach")
                    .isExhausted()
            }
            employeeFlow!!.test {
                awaitItemAndRunQuery()
                    .hasRow("alice", "Zach")
                    .hasRow("bob", "Zach")
                    .hasRow("eve", "Zach")
                    .isExhausted()
            }
        }
    }

    @Test
    fun executeSqlAndTriggerWithNoTables() = runBoundTest {
        db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
            db.executeAndTrigger(
                emptySet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
        }
    }

    @Test
    fun executeSqlThrowsAndDoesNotTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test { }
        try {
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE not_a_table SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
            )
            Assert.fail()
        } catch (ignored: SQLException) {
        }
    }

    @Test
    fun executeSqlWithArgsAndTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @Test
    fun executeSqlWithArgsThrowsAndDoesNotTrigger() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test { }
        try {
            db.executeAndTrigger(
                TestDb.TABLE_EMPLOYEE,
                "UPDATE not_a_table SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
            Assert.fail()
        } catch (ignored: SQLException) {
        }
    }

    @Test
    fun executeSqlWithArgsAndTriggerWithMultipleTables() = runBoundTest {
        val managerFlow = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerFlow.test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }

        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }

        db.executeAndTrigger(
            TestDb.BOTH_TABLES.toSet(),
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
        )
        managerFlow.test {
            awaitItemAndRunQuery()
                .hasRow("Zach", "Zach")
                .isExhausted()
        }
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }


    @Test
    fun executeSqlWithArgsAndTriggerWithNoTables() = runBoundTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
            db.executeAndTrigger(
                emptySet(),
                "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?", "Zach"
            )
        }
    }

    @Test
    fun executeInsertAndTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                    + "VALUES ('Chad Chadson', 'chad')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }

    }

    @Test
    fun executeInsertAndDontTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT OR IGNORE INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                    + "VALUES ('Alice Allison', 'alice')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
        }
    }

    @Test
    fun executeInsertAndTriggerMultipleTables() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                    + "VALUES ('Chad Chadson', 'chad')"
        )
        val managerObserver = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerObserver.test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
        val employeeAndManagerTables = Collections.unmodifiableSet(
            HashSet(
                TestDb.BOTH_TABLES
            )
        )
        db.executeInsert(employeeAndManagerTables, statement)
        managerObserver.test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }
    }

    @Test
    fun executeInsertAndTriggerNoTables() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                    + "VALUES ('Chad Chadson', 'chad')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(emptySet(), statement)
        }
    }

    @Test
    fun executeInsertThrowsAndDoesNotTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") "
                    + "VALUES ('Alice Allison', 'alice')"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @Test
    fun executeInsertWithArgsAndTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") VALUES (?, ?)"
        )
        statement.bindString(1, "Chad Chadson")
        statement.bindString(2, "chad")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("chad", "Chad Chadson")
                .isExhausted()
        }
    }

    @Test
    fun executeInsertWithArgsThrowsAndDoesNotTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "INSERT INTO "
                    + TestDb.TABLE_EMPLOYEE + " (" + TestDb.EmployeeTable.NAME + ", " + TestDb.EmployeeTable.USERNAME + ") VALUES (?, ?)"
        )
        statement.bindString(1, "Alice Aliison")
        statement.bindString(2, "alice")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {

                try {
                    db.executeInsert(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndDontTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            ""
                    + "UPDATE " + TestDb.TABLE_EMPLOYEE
                    + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
                    + " WHERE " + TestDb.EmployeeTable.NAME + " = 'Rob'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTriggerWithMultipleTables() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        val managerObserver = db.createQuery(TestDb.TABLE_MANAGER, TestDb.SELECT_MANAGER_LIST)
        managerObserver.test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()
        }
        val employeeFlow = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
        val employeeAndManagerTables = Collections.unmodifiableSet(HashSet(TestDb.BOTH_TABLES))
        db.executeUpdateDelete(employeeAndManagerTables, statement)
        employeeFlow.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
        managerObserver.test {
            awaitItemAndRunQuery()
                .hasRow("Zach", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteAndTriggerWithNoTables() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = 'Zach'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(emptySet(), statement)
        }
    }

    @Test
    fun executeUpdateDeleteThrowsAndDoesNotTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + EmployeeTable.USERNAME + " = 'alice'"
        )
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
                cancel()
            }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteWithArgsAndTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.NAME + " = ?"
        )
        statement.bindString(1, "Zach")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
            awaitItemAndRunQuery()
                .hasRow("alice", "Zach")
                .hasRow("bob", "Zach")
                .hasRow("eve", "Zach")
                .isExhausted()
        }
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.HONEYCOMB)
    @Test
    fun executeUpdateDeleteWithArgsThrowsAndDoesNotTrigger() = runBoundTest {
        val statement: SupportSQLiteStatement = real.compileStatement(
            "UPDATE " + TestDb.TABLE_EMPLOYEE + " SET " + TestDb.EmployeeTable.USERNAME + " = ?"
        )
        statement.bindString(1, "alice")
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .drop(1) // Skip initial
            .test {
                try {
                    db.executeUpdateDelete(TestDb.TABLE_EMPLOYEE, statement)
                    Assert.fail()
                } catch (ignored: SQLException) {
                }
            }
    }

    @Test
    fun transactionOnlyNotifiesOnceAndTransactionDoesNotThrow() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()

            db.withTransaction {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            }

            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()

            cancel()
        }
    }

    @Test
    fun queryCreatedDuringWithTransactionThrows() = runBoundTest {
        db.withTransaction {
            try {
                db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
                Assert.fail()
            } catch (e: IllegalStateException) {
                Truth.assertThat(e.message).startsWith("Cannot create observable query in transaction.")
            }
        }
    }

    @Test
    fun querySubscribedToDuringWithTransactionThrows() = runBoundTest {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        db.withTransaction {
            query.test {
                awaitItem()
                Truth.assertThat(awaitError())
                    .hasMessageThat()
                    .contains("Cannot subscribe to observable query in a transaction.")
            }
        }
    }

    @Test
    fun queryCreatedBeforeWithTransactionButSubscribedAfter() = runBoundTest {
        val query = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
        db.withTransaction {
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
        }
        query.test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun cancelledWithTransactionRollsBackAndReleasesConnection() = runBoundTest {
        val inserted = CompletableDeferred<Unit>()
        val transaction = launch {
            db.withTransaction {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                inserted.complete(Unit)
                awaitCancellation()
            }
        }
        inserted.await()
        transaction.cancelAndJoin()

        // A leaked transaction blocks this read forever inside SQLiteConnectionPool, so run it
        // outside this scope where runTest won't wait on it and the timeout can still fire.
        val employeeCount = backgroundScope.async {
            db.query(TestDb.SELECT_EMPLOYEES).use { cursor -> cursor.count }
        }
        Truth.assertThat(withTimeoutOrNull(5_000) { employeeCount.await() }).isEqualTo(3)
    }

    @Test
    fun runInTransactionSendsHeldTriggersOnceAfterCommit() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()

            db.runInTransaction {
                real.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.sendTableTrigger(TestDb.TABLE_EMPLOYEE)
                real.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                db.sendTableTrigger(TestDb.TABLE_EMPLOYEE)
            }

            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
            expectNoEvents()
        }
    }

    @Test
    fun runInTransactionDropsHeldTriggersOnRollback() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery().close()

            try {
                db.runInTransaction {
                    real.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                    db.sendTableTrigger(TestDb.TABLE_EMPLOYEE)
                    throw IllegalStateException("rollback")
                }
            } catch (expected: IllegalStateException) {
            }

            expectNoEvents()
        }
    }

    @Test
    fun runInTransactionInsideWithTransactionHandsTriggersToOuter() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery().close()

            try {
                db.withTransaction {
                    db.runInTransaction {
                        real.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                        db.sendTableTrigger(TestDb.TABLE_EMPLOYEE)
                    }
                    throw IllegalStateException("rollback outer")
                }
            } catch (expected: IllegalStateException) {
            }

            expectNoEvents()
        }
    }

    @Test
    fun runInTransactionForwardsListenerCallbacks() {
        val events = mutableListOf<String>()
        val listener = object : SQLiteTransactionListener {
            override fun onBegin() {
                events += "begin"
            }

            override fun onCommit() {
                events += "commit"
            }

            override fun onRollback() {
                events += "rollback"
            }
        }

        db.runInTransaction(listener) { }
        try {
            db.runInTransaction(listener) { throw IllegalStateException("rollback") }
        } catch (expected: IllegalStateException) {
        }

        Truth.assertThat(events).containsExactly("begin", "commit", "begin", "rollback").inOrder()
    }


    @Test
    fun synchronousQueryDuringTransaction() = runBoundTest {
        db.withTransaction {
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun synchronousQueryDuringTransactionSeesChanges() = runBoundTest {
        db.withTransaction {
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
            db.query(TestDb.SELECT_EMPLOYEES)
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun synchronousQueryWithSupportSQLiteQueryDuringTransaction() = runBoundTest {
        db.withTransaction {
            db.query(SimpleSQLiteQuery(TestDb.SELECT_EMPLOYEES))
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
        }
    }

    @Test
    fun nestedTransactionsOnlyNotifyOnce() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.withTransaction {
                db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                db.withTransaction {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                }
            }
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun nestedTransactionsOnMultipleTables() = runBoundTest {
        db.createQuery(TestDb.BOTH_TABLES, TestDb.SELECT_MANAGER_LIST).test {
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .isExhausted()

            db.withTransaction {
                db.withTransaction {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                }
                db.withTransaction {
                    db.insert(TestDb.TABLE_MANAGER, SQLiteDatabase.CONFLICT_NONE, TestDb.manager(testDb.aliceId, testDb.bobId))
                }
            }
            awaitItemAndRunQuery()
                .hasRow("Eve Evenson", "Alice Allison")
                .hasRow("Alice Allison", "Bob Bobberson")
                .isExhausted()
        }
    }

    @Test
    fun emptyTransactionDoesNotNotify() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            db.withTransaction { }
        }
    }

    @Test
    fun transactionRollbackDoesNotNotify() = runBoundTest {
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES).test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .isExhausted()
            try {
                db.withTransaction {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                    error("No call to set successful.")
                }
            } catch (e: Exception) {
                Assert.assertTrue(e.message!!.startsWith("No call to set successful."))
            }
        }
    }

    @Test
    fun nonExclusiveTransactionWorks() = runBoundTest {
        db.withNonExclusiveTransaction {
            db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("hans", "Hans Hanson"))
        }
        //Simple query
        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOne(TestDb.Employee.MAPPER)
            .test {
                Truth.assertThat(awaitItem()).isEqualTo(TestDb.Employee("alice", "Alice Allison"))
            }
    }

    @Test
    fun suspendingTransactionsWontDeadlock() = runBoundTest {
        val dispatcher1 = newSingleThreadContext("dispatcher1")
        val dispatcher2 = newSingleThreadContext("dispatcher2")

        db.withTransaction {
            coroutineScope {
                launch(dispatcher1) {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
                }
                launch(dispatcher2) {
                    db.insert(TestDb.TABLE_EMPLOYEE, SQLiteDatabase.CONFLICT_NONE, TestDb.employee("nick", "Nick Nickers"))
                }
            }
        }

        db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " ORDER BY ${EmployeeTable.USERNAME}").test {
            awaitItemAndRunQuery()
                .hasRow("alice", "Alice Allison")
                .hasRow("bob", "Bob Bobberson")
                .hasRow("eve", "Eve Evenson")
                .hasRow("john", "John Johnson")
                .hasRow("nick", "Nick Nickers")
                .isExhausted()
        }
    }

    @Test
    fun badQueryThrows() = runBoundTest {
        try {
            db.query("SELECT * FROM missing")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badInsertThrows() = runBoundTest {
        try {
            db.insert("missing", SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"))
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badUpdateThrows() = runBoundTest {
        try {
            db.update("missing", SQLiteDatabase.CONFLICT_NONE, TestDb.employee("john", "John Johnson"), "1")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun badDeleteThrows() = runBoundTest {
        try {
            db.delete("missing", "1")
            Assert.fail()
        } catch (e: SQLiteException) {
            Truth.assertThat(e.message).contains("no such table: missing")
        }
    }

    @Test
    fun inSuspendTransaction() = runBoundTest {
        Truth.assertThat(db.inSuspendingTransaction).isFalse()
        db.withTransaction {
            Truth.assertThat(db.inSuspendingTransaction).isTrue()
        }
        Truth.assertThat(db.inSuspendingTransaction).isFalse()
        real.beginTransaction()
        Truth.assertThat(db.inSuspendingTransaction).isTrue()
        real.setTransactionSuccessful()
        real.endTransaction()
        Truth.assertThat(db.inSuspendingTransaction).isFalse()
    }
}
