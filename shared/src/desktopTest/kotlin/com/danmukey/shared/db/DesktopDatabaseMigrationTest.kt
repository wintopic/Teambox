package com.danmukey.shared.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopDatabaseMigrationTest {
    @Test
    fun freshDatabaseIsCreatedAtCurrentVersion() {
        val databaseFile = Files.createTempFile("danmukey-fresh-database", ".db")
        try {
            DesktopDatabaseDriverFactory(databaseFile.toFile()).createDriver().use { driver ->
                assertEquals(DanmuKeyDatabase.Schema.version, queryLong(driver, "PRAGMA user_version"))
                assertTrue(objectExists(driver, "table", "keyboard_pack"))
                assertTrue(objectExists(driver, "table", "content_follow_state"))
            }
        } finally {
            databaseFile.deleteIfExists()
        }
    }

    @Test
    fun historicalDatabasesFromVersionZeroThroughFourMigrateWithoutDataLoss() {
        assertEquals(5L, DanmuKeyDatabase.Schema.version, "新增迁移后需要扩展历史数据库样本")

        listOf(0L, 1L, 2L, 3L, 4L).forEach { storedVersion ->
            val databaseFile = Files.createTempFile("danmukey-migration-v$storedVersion", ".db")
            try {
                createHistoricalDatabase(databaseFile.toString(), storedVersion)

                DesktopDatabaseDriverFactory(databaseFile.toFile()).createDriver().use { migrated ->
                    assertTrue(objectExists(migrated, "table", "diagnostic_event"), "v$storedVersion")
                    assertTrue(objectExists(migrated, "table", "target_rule_revision"), "v$storedVersion")
                    assertTrue(objectExists(migrated, "index", "episode_map_section_idx"), "v$storedVersion")
                    assertTrue(objectExists(migrated, "table", "content_follow_state"), "v$storedVersion")
                    assertEquals(DanmuKeyDatabase.Schema.version, queryLong(migrated, "PRAGMA user_version"))
                    assertEquals(1L, queryLong(migrated, "SELECT COUNT(*) FROM keyboard_pack"))
                    assertEquals(1L, queryLong(migrated, "SELECT COUNT(*) FROM keyboard_section"))
                    assertEquals(1L, queryLong(migrated, "SELECT COUNT(*) FROM episode_map"))

                    val effectiveVersion = if (storedVersion == 0L) 1L else storedVersion
                    val expectedDiagnosticRows = if (effectiveVersion >= 2L) 1L else 0L
                    val expectedTargetRuleRows = if (effectiveVersion >= 3L) 1L else 0L
                    assertEquals(
                        expectedDiagnosticRows,
                        queryLong(migrated, "SELECT COUNT(*) FROM diagnostic_event"),
                        "v$storedVersion 的诊断数据应保持不变",
                    )
                    assertEquals(
                        expectedTargetRuleRows,
                        queryLong(migrated, "SELECT COUNT(*) FROM target_rule_revision"),
                        "v$storedVersion 的目标规则数据应保持不变",
                    )
                }
            } finally {
                databaseFile.deleteIfExists()
            }
        }
    }

    @Test
    fun futureDatabaseVersionIsRejectedAndConnectionIsClosed() {
        val databaseFile = Files.createTempFile("danmukey-future-version", ".db")
        try {
            JdbcSqliteDriver("jdbc:sqlite:${databaseFile.toAbsolutePath()}").use { driver ->
                DanmuKeyDatabase.Schema.create(driver)
                driver.execute(
                    identifier = null,
                    sql = "PRAGMA user_version = ${DanmuKeyDatabase.Schema.version + 1L}",
                    parameters = 0,
                ).value
            }

            val error = assertFailsWith<IllegalArgumentException> {
                DesktopDatabaseDriverFactory(databaseFile.toFile()).createDriver()
            }
            assertTrue("高于当前程序支持" in error.message.orEmpty())
        } finally {
            databaseFile.deleteIfExists()
        }
    }

    private fun createHistoricalDatabase(path: String, storedVersion: Long) {
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            DanmuKeyDatabase.Schema.create(driver)
            val effectiveVersion = if (storedVersion == 0L) 1L else storedVersion

            if (effectiveVersion < 5L) {
                driver.execute(null, "DROP TABLE content_follow_state", 0).value
            }
            if (effectiveVersion < 4L) {
                driver.execute(null, "DROP INDEX episode_map_section_idx", 0).value
            }
            if (effectiveVersion < 3L) {
                driver.execute(null, "DROP TABLE target_rule_revision", 0).value
            }
            if (effectiveVersion < 2L) {
                driver.execute(null, "DROP TABLE diagnostic_event", 0).value
            }

            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO keyboard_pack(
                      id, name, author, version, description, cover_path, created_at, updated_at
                    ) VALUES ('legacy-pack', '旧版内容包', 'migration-test', 1, '', NULL, 100, 100)
                """.trimIndent(),
                parameters = 0,
            ).value
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO keyboard_section(
                      id, pack_id, title, type, episode_number, sort_order
                    ) VALUES ('legacy-section', 'legacy-pack', '第一集', 'Episode', 1, 0)
                """.trimIndent(),
                parameters = 0,
            ).value
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO episode_map(
                      id, target_id, normalized_title, section_id, confidence, updated_at
                    ) VALUES ('legacy-map', 'legacy-target', '第一集', 'legacy-section', 1.0, 100)
                """.trimIndent(),
                parameters = 0,
            ).value

            if (effectiveVersion >= 2L) {
                driver.execute(
                    identifier = null,
                    sql = """
                        INSERT INTO diagnostic_event(
                          id, level, event_code, target_id, task_id, details_json, created_at
                        ) VALUES ('legacy-diagnostic', 'Info', 'legacy_event', NULL, NULL, '{}', 100)
                    """.trimIndent(),
                    parameters = 0,
                ).value
            }
            if (effectiveVersion >= 3L) {
                driver.execute(
                    identifier = null,
                    sql = """
                        INSERT INTO target_rule_revision(
                          rule_id, revision, source, signature_state, state,
                          envelope_json, imported_at, activated_at
                        ) VALUES (
                          'legacy-rule', 1, 'LocalImport', 'Verified', 'Observation',
                          '{}', 100, NULL
                        )
                    """.trimIndent(),
                    parameters = 0,
                ).value
            }

            driver.execute(
                identifier = null,
                sql = "PRAGMA user_version = $storedVersion",
                parameters = 0,
            ).value
        }
    }

    private fun objectExists(driver: SqlDriver, type: String, name: String): Boolean =
        queryLong(
            driver,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = '$type' AND name = '$name'",
        ) == 1L

    private fun queryLong(driver: SqlDriver, sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value
}
