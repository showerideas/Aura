package com.showerideas.aura

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.Migrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Migration test harness — established at v1 so future schema bumps have a
 * working scaffold to extend. Right now there are no migrations to test
 * (we're at version 1), but this verifies:
 *   - the v1 schema file is exported to app/schemas
 *   - MigrationTestHelper can open it
 *   - Room can spin up a database against the latest schema
 *
 * When a v1 → v2 migration is added, add a new test alongside [v1_schema_opens]
 * that calls `helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    companion object {
        private const val TEST_DB = "migration-test"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun v1_schema_opens() {
        // Create a fresh v1 database from the exported schema and close it.
        helper.createDatabase(TEST_DB, 1).apply { close() }

        // Reopen via the real Room build; if migrations weren't wired
        // correctly Room would throw IllegalStateException here.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        )
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(*Migrations.ALL)
            .build()
        assertNotNull(db.contactDao())
        assertNotNull(db.profileDao())
        db.close()
    }

    /**
     * PR-14: validate the 1→ 2 migration. Inserts a sample contact at v1,
     * runs the migration, and asserts:
     *   - existing rows survive untouched (no data loss)
     *   - the new blocked_endpoints table is now usable
     */
    @Test
    @Throws(IOException::class)
    fun migrate_1_to_2_preserves_data_and_creates_blocked_endpoints() {
        // Seed a v1 database with one contact.
        helper.createDatabase(TEST_DB, 1).use { v1 ->
            v1.execSQL(
                """
                INSERT INTO contacts (
                    id, displayName, phone, email, company, title, website, bio,
                    avatarUri, sourceEndpointId, rssiAtExchange, receivedAt,
                    isFavorite, notes
                ) VALUES (
                    'c1', 'Ada Lovelace', '111', 'ada@x.com', '', '', '', '',
                    '', 'ep-1', 0, 1700000000000, 0, ''
                )
                """.trimIndent()
            )
        }

        // Run the migration in isolation and let Room validate the
        // resulting schema against the exported v2 JSON.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 2, true, Migrations.MIGRATION_1_2
        )

        // Existing row preserved.
        migrated.query("SELECT COUNT(*) FROM contacts WHERE id = 'c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // New table exists and is empty.
        migrated.query("SELECT COUNT(*) FROM blocked_endpoints").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // And it accepts inserts.
        migrated.execSQL(
            "INSERT INTO blocked_endpoints (endpointId, blockedAt, note) VALUES ('ep-2', 1, '')"
        )
        migrated.query("SELECT note FROM blocked_endpoints WHERE endpointId = 'ep-2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("", c.getString(0))
        }
        migrated.close()
    }

    /**
     * FIX-2 / PR-18: validate the 2→3 migration. Starts from a v2 database
     * containing both contacts and blocked_endpoints rows, runs the migration,
     * and asserts:
     *   - all existing rows survive untouched (no data loss)
     *   - the new known_peers table is created and accepts inserts
     */
    @Test
    @Throws(IOException::class)
    fun migrate_2_to_3_preserves_data_and_creates_known_peers() {
        // Build a v2 database with seed data in both existing tables.
        helper.createDatabase(TEST_DB, 2).use { v2 ->
            v2.execSQL(
                """
                INSERT INTO contacts (
                    id, displayName, phone, email, company, title, website, bio,
                    avatarUri, sourceEndpointId, rssiAtExchange, receivedAt,
                    isFavorite, notes
                ) VALUES (
                    'c2', 'Grace Hopper', '222', 'grace@x.com', '', '', '', '',
                    '', 'ep-2', 0, 1700000000001, 0, ''
                )
                """.trimIndent()
            )
            v2.execSQL(
                "INSERT INTO blocked_endpoints (endpointId, blockedAt, note) VALUES ('ep-bad', 1000, 'spammer')"
            )
        }

        // Run MIGRATION_2_3 and let Room validate the resulting schema.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 3, true, Migrations.MIGRATION_2_3
        )

        // Existing contacts row preserved.
        migrated.query("SELECT COUNT(*) FROM contacts WHERE id = 'c2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // Existing blocked_endpoints row preserved.
        migrated.query("SELECT note FROM blocked_endpoints WHERE endpointId = 'ep-bad'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("spammer", c.getString(0))
        }
        // New known_peers table exists, starts empty, and accepts inserts.
        migrated.query("SELECT COUNT(*) FROM known_peers").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        migrated.execSQL(
            """
            INSERT INTO known_peers (endpointId, identityPublicKeyBase64, firstSeenAt, lastSeenAt)
            VALUES ('ep-tofu', 'dGVzdA==', 1000, 2000)
            """.trimIndent()
        )
        migrated.query("SELECT identityPublicKeyBase64 FROM known_peers WHERE endpointId = 'ep-tofu'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("dGVzdA==", c.getString(0))
        }
        migrated.close()
    }
}
