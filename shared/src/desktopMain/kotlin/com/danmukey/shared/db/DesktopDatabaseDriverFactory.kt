package com.danmukey.shared.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.danmukey.shared.model.DatabaseDriverFactory
import java.io.File

class DesktopDatabaseDriverFactory(
    private val databaseFile: File,
) : DatabaseDriverFactory {
    override fun createDriver(): SqlDriver {
        databaseFile.parentFile?.mkdirs()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
        try {
            if (!tableExists(driver, "keyboard_pack")) {
                DanmuKeyDatabase.Schema.create(driver)
                setSchemaVersion(driver, DanmuKeyDatabase.Schema.version)
                return driver
            }

            val storedVersion = readSchemaVersion(driver)
            val oldVersion = if (storedVersion == 0L) 1L else storedVersion
            require(oldVersion <= DanmuKeyDatabase.Schema.version) {
                "数据库版本 $oldVersion 高于当前程序支持的 ${DanmuKeyDatabase.Schema.version}"
            }
            if (oldVersion < DanmuKeyDatabase.Schema.version) {
                DanmuKeyDatabase.Schema.migrate(
                    driver = driver,
                    oldVersion = oldVersion,
                    newVersion = DanmuKeyDatabase.Schema.version,
                )
                setSchemaVersion(driver, DanmuKeyDatabase.Schema.version)
            }
            return driver
        } catch (error: Throwable) {
            driver.close()
            throw error
        }
    }

    private fun tableExists(driver: SqlDriver, name: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 1,
    ) {
        bindString(0, name)
    }.value

    private fun readSchemaVersion(driver: SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

    private fun setSchemaVersion(driver: SqlDriver, version: Long) {
        driver.execute(
            identifier = null,
            sql = "PRAGMA user_version = $version",
            parameters = 0,
        ).value
    }
}
