package com.shaterguy.fc2weeklyranker

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shaterguy.fc2weeklyranker.data.AppDatabase
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.search.SearchDatabase
import com.shaterguy.fc2weeklyranker.search.SearchRequest
import com.shaterguy.fc2weeklyranker.search.SearchSessionEntity
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIsolationContractTest {
    @Test
    fun `content modes expose stable board local id and host isolation contracts`() {
        val type = Class.forName("com.shaterguy.fc2weeklyranker.domain.ContentMode")
        val constants = type.enumConstants.associateBy { (it as Enum<*>).name }
        val fc2 = constants.getValue("FC2")
        val jav = constants.getValue("JAV")
        val boardTable = type.getMethod("getBoardTable")
        val sourceKey = type.getMethod("getSourceKey")
        val defaultBaseUrl = type.getMethod("getDefaultBaseUrl")
        val localPostId = type.getMethod("localPostId", String::class.java)

        assertEquals("javfc2", boardTable.invoke(fc2))
        assertEquals("javc", boardTable.invoke(jav))
        assertEquals("FC2", sourceKey.invoke(fc2))
        assertEquals("JAV", sourceKey.invoke(jav))
        assertEquals("https://01.avsee.is", defaultBaseUrl.invoke(fc2))
        assertEquals("https://02.avsee.is", defaultBaseUrl.invoke(jav))
        assertEquals("8123", localPostId.invoke(fc2, "8123"))
        assertEquals("jav:8123", localPostId.invoke(jav, "8123"))
        assertNotEquals(localPostId.invoke(fc2, "8123"), localPostId.invoke(jav, "8123"))
    }

    @Test
    fun `main database v3 to v4 migration classifies legacy posts as FC2`() {
        assertTrue(PostEntity::class.java.declaredFields.any { it.name == "sourceKey" })
        val migration = migration(AppDatabase::class.java, "MIGRATION_3_4")
        assertEquals(3, migration.startVersion)
        assertEquals(4, migration.endVersion)
        val sql = captureSql(migration)
        assertTrue(sql.any { it.contains("ALTER TABLE posts ADD COLUMN sourceKey TEXT NOT NULL DEFAULT 'FC2'") })
        assertTrue(sql.any { it.contains("CREATE INDEX") && it.contains("posts") && it.contains("sourceKey") })
    }

    @Test
    fun `search database v1 to v2 migration classifies legacy session as FC2`() {
        assertTrue(SearchSessionEntity::class.java.declaredFields.any { it.name == "sourceKey" })
        assertTrue(SearchRequest::class.java.declaredFields.any { it.name == "sourceKey" })
        val migration = migration(SearchDatabase::class.java, "MIGRATION_1_2")
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)
        val sql = captureSql(migration)
        assertTrue(sql.any { it.contains("ALTER TABLE search_session ADD COLUMN sourceKey TEXT NOT NULL DEFAULT 'FC2'") })
    }

    private fun migration(owner: Class<*>, name: String): Migration {
        val companion = owner.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val getter = companion.javaClass.methods.singleOrNull { it.name == "get$name" }
            ?: error("Missing migration contract: ${owner.simpleName}.$name")
        return getter.invoke(companion) as Migration
    }

    private fun captureSql(migration: Migration): List<String> {
        val sql = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                (args?.firstOrNull() as? String)?.let(sql::add)
            }
            null
        } as SupportSQLiteDatabase
        migration.migrate(database)
        return sql
    }
}
