package com.danmukey.shared.model

import app.cash.sqldelight.db.SqlDriver
import com.danmukey.shared.db.DanmuKeyDatabase

interface DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(factory: DatabaseDriverFactory): DanmuKeyDatabase =
    DanmuKeyDatabase(factory.createDriver())
