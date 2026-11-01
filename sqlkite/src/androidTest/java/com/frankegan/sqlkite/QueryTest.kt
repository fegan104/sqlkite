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
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth
import com.frankegan.sqlkite.TestDb.Employee
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

class QueryTest {

    private lateinit var db: KiteDatabase

    @Before
    fun setUp() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
            .callback(TestDb())
            .build()
        val factory: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(configuration)
        val sqlKite = SqlKite.Builder().build()
        val dispatcher = StandardTestDispatcher()
        val executor = Executors.newSingleThreadExecutor()
        db = sqlKite.wrapDatabaseHelper(helper, dispatcher, executor)
    }

    @Test
    fun mapToOne() = runTest {
        val employees: Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOne { Employee.MAPPER(it) }
            .first()
        Truth.assertThat(employees).isEqualTo(Employee("alice", "Alice Allison"))
    }

    @Test
    fun mapToOneThrowsOnMultipleRows() = runTest {
        val employees: Flow<Employee> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 2") //
            .mapToOne { Employee.MAPPER(it) }
        try {
            employees.first()
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e).hasMessage("Cursor returned more than 1 row")
        }
    }

    @Test
    fun mapToOneOrDefault() = runTest {
        val employees: Employee = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 1")
            .mapToOneOrDefault(Employee("fred", "Fred Frederson")) { Employee.MAPPER(it) }
            .first()
        Truth.assertThat(employees).isEqualTo(Employee("alice", "Alice Allison"))
    }

    @Test
    fun mapToOneOrDefaultThrowsOnMultipleRows() = runTest {
        val employees: Flow<Employee> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " LIMIT 2") //
            .mapToOneOrDefault(Employee("fred", "Fred Frederson")) {
                Employee.MAPPER(it)
            }
        try {
            employees.first()
            Assert.fail()
        } catch (e: IllegalStateException) {
            Truth.assertThat(e).hasMessage("Cursor returned more than 1 row")
        }
    }

    @Test
    fun mapToList() = runTest {
        val employees: List<Employee> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES)
            .mapToList(Employee.MAPPER)
            .first()
        Truth.assertThat(employees).containsExactly( //
            Employee("alice", "Alice Allison"),  //
            Employee("bob", "Bob Bobberson"),  //
            Employee("eve", "Eve Evenson")
        )
    }

    @Test
    fun mapToListEmptyWhenNoRows() = runTest {
        val employees: List<Employee?> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES + " WHERE 1=2")
            .mapToList(Employee.MAPPER)
            .first()
        Truth.assertThat(employees).isEmpty()
    }

    @Test
    fun mapToListReturnsNullOnMapperNull() = runTest {
        var count = 0

        val mapToNull = { cursor: Cursor ->
            if (count++ == 2) null else Employee.MAPPER(cursor)
        }

        val employees: List<Employee?> = db.createQuery(TestDb.TABLE_EMPLOYEE, TestDb.SELECT_EMPLOYEES) //
            .mapToList(mapToNull)
            .first()
        Truth.assertThat(employees).containsExactly(
            Employee("alice", "Alice Allison"),
            Employee("bob", "Bob Bobberson"),
            null
        )
    }
}