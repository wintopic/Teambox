package com.danmukey.shared.model

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.danmukey.shared.db.DanmuKeyDatabase

class AndroidDatabaseDriverFactory(
    private val context: Context,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = DanmuKeyDatabase.Schema,
        context = context,
        name = "danmukey.db",
    )
}
