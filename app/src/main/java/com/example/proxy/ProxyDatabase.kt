package com.example.proxy

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class ProxyAccount(
    val id: Long = 0,
    val name: String,
    val provider: String, // OpenAI, Codex, xAI, Custom
    val apiKey: String,
    val baseUrl: String,
    val isActive: Boolean = true,
    val requestCount: Long = 0
)

data class ProxyLog(
    val id: Long = 0,
    val timestamp: Long,
    val method: String,
    val path: String,
    val statusCode: Int,
    val latencyMs: Long,
    val provider: String
)

data class ProxyMetrics(
    val totalRequests: Long = 0,
    val successRequests: Long = 0,
    val errorRequests: Long = 0,
    val averageLatencyMs: Long = 0,
    val activeAccounts: Int = 0
)

class ProxyDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "codex_proxy.db"
        const val DB_VERSION = 1

        private const val TABLE_ACCOUNTS = "accounts"
        private const val TABLE_LOGS = "logs"

        @Volatile
        private var INSTANCE: ProxyDatabase? = null

        fun getInstance(context: Context): ProxyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProxyDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ACCOUNTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                provider TEXT NOT NULL,
                api_key TEXT NOT NULL,
                base_url TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                request_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_LOGS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                status_code INTEGER NOT NULL,
                latency_ms INTEGER NOT NULL,
                provider TEXT NOT NULL
            )
            """.trimIndent()
        )

        // Seed default developer account for instant testing
        val cv = ContentValues().apply {
            put("name", "Local Dev Account")
            put("provider", "Codex")
            put("api_key", "sk-dev-${UUID.randomUUID().toString().take(12)}")
            put("base_url", "https://api.openai.com/v1")
            put("is_active", 1)
            put("request_count", 0)
        }
        db.insert(TABLE_ACCOUNTS, null, cv)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACCOUNTS")
        onCreate(db)
    }

    fun getAllAccounts(): List<ProxyAccount> {
        val list = mutableListOf<ProxyAccount>()
        val db = readableDatabase
        val cursor = db.query(TABLE_ACCOUNTS, null, null, null, null, null, "id ASC")
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ProxyAccount(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        name = it.getString(it.getColumnIndexOrThrow("name")),
                        provider = it.getString(it.getColumnIndexOrThrow("provider")),
                        apiKey = it.getString(it.getColumnIndexOrThrow("api_key")),
                        baseUrl = it.getString(it.getColumnIndexOrThrow("base_url")),
                        isActive = it.getInt(it.getColumnIndexOrThrow("is_active")) == 1,
                        requestCount = it.getLong(it.getColumnIndexOrThrow("request_count"))
                    )
                )
            }
        }
        return list
    }

    fun insertAccount(account: ProxyAccount): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("name", account.name)
            put("provider", account.provider)
            put("api_key", account.apiKey)
            put("base_url", account.baseUrl)
            put("is_active", if (account.isActive) 1 else 0)
            put("request_count", account.requestCount)
        }
        return db.insert(TABLE_ACCOUNTS, null, cv)
    }

    fun deleteAccount(id: Long) {
        val db = writableDatabase
        db.delete(TABLE_ACCOUNTS, "id = ?", arrayOf(id.toString()))
    }

    fun setAccountActive(id: Long, active: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_active", if (active) 1 else 0)
        }
        db.update(TABLE_ACCOUNTS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun incrementAccountRequests(id: Long) {
        val db = writableDatabase
        db.execSQL("UPDATE $TABLE_ACCOUNTS SET request_count = request_count + 1 WHERE id = ?", arrayOf(id))
    }

    fun insertLog(log: ProxyLog) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("timestamp", log.timestamp)
            put("method", log.method)
            put("path", log.path)
            put("status_code", log.statusCode)
            put("latency_ms", log.latencyMs)
            put("provider", log.provider)
        }
        db.insert(TABLE_LOGS, null, cv)
        // Keep logs bounded to last 200 entries to prevent growth
        db.execSQL("DELETE FROM $TABLE_LOGS WHERE id NOT IN (SELECT id FROM $TABLE_LOGS ORDER BY id DESC LIMIT 200)")
    }

    fun getRecentLogs(limit: Int = 50): List<ProxyLog> {
        val list = mutableListOf<ProxyLog>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LOGS, null, null, null, null, null, "id DESC", limit.toString())
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ProxyLog(
                        id = it.getLong(it.getColumnIndexOrThrow("id")),
                        timestamp = it.getLong(it.getColumnIndexOrThrow("timestamp")),
                        method = it.getString(it.getColumnIndexOrThrow("method")),
                        path = it.getString(it.getColumnIndexOrThrow("path")),
                        statusCode = it.getInt(it.getColumnIndexOrThrow("status_code")),
                        latencyMs = it.getLong(it.getColumnIndexOrThrow("latency_ms")),
                        provider = it.getString(it.getColumnIndexOrThrow("provider"))
                    )
                )
            }
        }
        return list
    }

    fun clearLogs() {
        val db = writableDatabase
        db.delete(TABLE_LOGS, null, null)
    }

    fun getMetrics(): ProxyMetrics {
        val db = readableDatabase
        var total = 0L
        var success = 0L
        var error = 0L
        var sumLatency = 0L

        val cursor = db.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN status_code < 400 THEN 1 ELSE 0 END), SUM(CASE WHEN status_code >= 400 THEN 1 ELSE 0 END), SUM(latency_ms) FROM $TABLE_LOGS",
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                total = it.getLong(0)
                success = it.getLong(1)
                error = it.getLong(2)
                sumLatency = it.getLong(3)
            }
        }

        val activeAccountsCount = getAllAccounts().count { it.isActive }
        val avgLatency = if (total > 0) sumLatency / total else 0L

        return ProxyMetrics(
            totalRequests = total,
            successRequests = success,
            errorRequests = error,
            averageLatencyMs = avgLatency,
            activeAccounts = activeAccountsCount
        )
    }
}
