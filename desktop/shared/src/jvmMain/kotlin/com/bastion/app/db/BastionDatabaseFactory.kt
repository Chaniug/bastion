package com.bastion.app.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * JVM 端 SQLDelight 驱动工厂返回结果：同时持有 [database] 与底层 [driver]。
 *
 * 业务存储层（[SqlDelightBitwardenRepositoryStore]）在生成自增主键时需要直接执行
 * 原生 SQL（[SqlDriver]），而生成的 [BastionDatabase] 不对外暴露 driver，
 * 因此此处一并返回。
 */
data class BastionDatabaseBundle(
    val database: BastionDatabase,
    val driver: SqlDriver
)

/**
 * JVM 端 SQLDelight 驱动工厂。
 *
 * - 全新库：直接执行 [BastionDatabase.Schema.create]。
 * - 旧库（Phase 0 占位 schema：password_entries.id 为 TEXT）:
 *   重建全部表以适配 Phase 3 的 INTEGER 主键 schema。
 *   由于 Phase 0/1 阶段业务只走内存存储、password_entries 从未被业务写入，
 *   重建不会丢失真实数据。
 */
object BastionDatabaseFactory {

    private val ALL_TABLES = listOf(
        "password_entries",
        "bitwarden_vaults",
        "bitwarden_folders",
        "bitwarden_conflict_backups",
        "bitwarden_pending_operations",
        "preferences",
        "local_keepass_databases",
        "keepass_remote_sources",
        "keepass_remote_sync_states"
    )

    fun create(dbPath: String): BastionDatabaseBundle {
        val url = "jdbc:sqlite:$dbPath"
        val driver = JdbcSqliteDriver(url)
        ensureSchema(driver)
        return BastionDatabaseBundle(BastionDatabase(driver), driver)
    }

    private fun ensureSchema(driver: SqlDriver) {
        val tableExists = tableExists(driver, "password_entries")
        if (!tableExists) {
            BastionDatabase.Schema.create(driver)
            return
        }
        // 已存在 password_entries：检测是否为旧 schema（id 为 TEXT）
        if (isLegacyTextIdSchema(driver)) {
            driver.execute(null, "PRAGMA foreign_keys=OFF", 0)
            ALL_TABLES.forEach { driver.execute(null, "DROP TABLE IF EXISTS $it", 0) }
            BastionDatabase.Schema.create(driver)
        }
        // 否则 schema 已是最新，无需操作
    }

    private fun tableExists(driver: SqlDriver, table: String): Boolean {
        val cursor = driver.executeQuery(
            null,
            "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'",
            { c: SqlCursor -> QueryResult.Value(c) },
            0
        ) { }.value
        return cursor.next().value
    }

    private fun isLegacyTextIdSchema(driver: SqlDriver): Boolean {
        val cursor = driver.executeQuery(
            null,
            "PRAGMA table_info(password_entries)",
            { c: SqlCursor -> QueryResult.Value(c) },
            0
        ) { }.value
        while (cursor.next().value) {
            val colName = cursor.getString(1)
            if (colName == "id") {
                val type = cursor.getString(2) ?: ""
                return type.equals("TEXT", ignoreCase = true)
            }
        }
        return false
    }
}
