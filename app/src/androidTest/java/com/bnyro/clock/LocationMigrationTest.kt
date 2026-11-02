package com.bnyro.clock

import android.content.ContentValues
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bnyro.clock.data.database.AppDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocationMigrationTest {
    @Test fun bothSchema15VariantsRetainAlarmsAndSnooze() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val schema = JSONObject(instrumentation.context.assets.open("com.bnyro.clock.data.database.AppDatabase/15.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        for (hasSnooze in listOf(false, true)) {
            val name = "migration-location-$hasSnooze"
            context.deleteDatabase(name)
            try {
                context.openOrCreateDatabase(name, 0, null).use { old ->
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                    }
                    if (hasSnooze) old.execSQL("ALTER TABLE alarms ADD COLUMN snoozedUntil INTEGER DEFAULT NULL")
                    old.insertOrThrow("alarms", null, ContentValues().apply {
                        put("id", 7); put("time", 28_800_000L); put("label", "Keep me")
                        put("enabled", 1); put("days", "0,1,2,3,4,5,6"); put("vibrate", 1)
                        if (hasSnooze) put("snoozedUntil", 1_800_000_000_000L)
                    })
                    old.version = 15
                }
                val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
                    .addMigrations(AppDatabase.MIGRATION_15_16).build()
                try {
                    migrated.openHelper.writableDatabase.query("SELECT label,time,snoozedUntil,awayTime FROM alarms WHERE id=7").use { row ->
                        assertTrue(row.moveToFirst())
                        assertEquals("Keep me", row.getString(0)); assertEquals(28_800_000L, row.getLong(1))
                        if (hasSnooze) assertEquals(1_800_000_000_000L, row.getLong(2)) else assertTrue(row.isNull(2))
                        assertTrue(row.isNull(3))
                    }
                    assertEquals(16, migrated.openHelper.writableDatabase.version)
                } finally { migrated.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
