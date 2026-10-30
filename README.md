SQL Kite 🪁🍃
=========


A lightweight Kotlin library that makes working with `SupportSQLiteOpenHelper` & `ContentResolver` a breeze by introducing reactive
Flow based queries.


This library is a fork of Square's [SQL Brite](https://github.com/square/sqlbrite) but uses Kotlin Flows and suspending functions instead of RX Java primitives.

Usage
-----

Create a `SqlKite` instance which is an adapter for the library functionality.

```
val sqlKite = SqlKite.Builder().build()
```

Pass a `SupportSQLiteOpenHelper` instance and a `Dispatcher` to create a `KiteDatabase`.

```
val db = sqlKite.wrapDatabaseHelper(openHelper, Dispatchers.IO)
```

The `KiteDatabase.createQuery` method is similar to `SupportSQLiteDatabase.query` except it takes an
additional parameter of table(s) on which to listen for changes. Subscribe to the returned
`Flow<Query>` which will immediately notify with a `Query` to run.

```
val users: Flow<Query> = db.createQuery("users", "SELECT * FROM users")
users.collect {
    val cursor = query.run();
    // TODO parse data...
}
```

Unlike a traditional `query`, updates to the specified table(s) will trigger additional
notifications for as long as you remain subscribed to the observable. This means that when you
insert, update, or delete data, any subscribed queries will update with the new data instantly.

```
val queries = AtomicInteger()
users.collect {
    queries.getAndIncrement()
}
println("Queries: ${queries.get()}") // Prints 1

db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("jw", "Jake Wharton"))
db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("mattp", "Matt Precious"))
db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("strong", "Alec Strong"))

println("Queries: ${queries.get()}") // Prints 4
```

In the previous example we re-used the `KiteDatabase` object "db" for inserts. All insert, update,
or delete operations must go through this object in order to correctly notify subscribers.

Cancel the collector scope for the returned `Flow` to stop getting updates.

```
val queries = AtomicInteger()
val queryCollector = launch {
  users.collect {
    queries.getAndIncrement()
  }
}
System.out.println("Queries: ${queries.get()}") // Prints 1

db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("jw", "Jake Wharton"))
db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("mattp", "Matt Precious"))
queryCollector.cancel()

db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("strong", "Alec Strong"))

println("Queries: ${queries.get()}") // Prints 3
```

Use transactions to prevent large changes to the data from spamming your subscribers.

```
val queries = AtomicInteger()
users.collect {
    queries.getAndIncrement()
}
println("Queries: ${queries.get()}") // Prints 1

val transaction = db.withTransaction {
  db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("jw", "Jake Wharton"))
  db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("mattp", "Matt Precious"))
  db.insert("users", SQLiteDatabase.CONFLICT_ABORT, createUser("strong", "Alec Strong"))
}

println("Queries: ${queries.get()}") // Prints 2
```
Since queries are just regular Kotlin `Flow` objects, operators can also be used to
control the frequency of notifications to collectors.

```
users.debounce(500.milliseconds).collect {
  //TODO...
}
```

The `SqlKite.Query` object can be used as an extension function on `ContentResolver` for observing a query on another app's
content provider.

```
val query: Flow<SqlKite.Query> = contentResolver.observeQuery(/*...*/).flowOn(Dispatcher.IO)
```

The full power of Kotlin Flow's operators are available for combining, filtering, and triggering any
number of queries and data changes.



Philosophy
----------

SQL Kite's only responsibility is to be a mechanism for coordinating and composing the notification
of updates to tables such that you can update queries as soon as data changes.

This library is not an ORM. It is not a type-safe query mechanism. It won't serialize the same data classes
you use for Gson. It's not going to perform database migrations for you.



Download
--------
[![Sonatype Central](https://maven-badges.sml.io/sonatype-central/com.frankegan/sqlkite/badge.svg)](https://maven-badges.sml.io/sonatype-central/com.frankegan/sqlkite/)

```groovy
//Project level build.gradle
repositories {
    mavenCentral()
}

//App level build.gradle
implementation 'com.frankegan:sqlkite:<x.y.z>'
```

License
-------

    Copyright 2021 Frank Egan
    Copyright 2015 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

