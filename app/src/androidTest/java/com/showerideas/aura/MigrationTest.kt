package com.showerideas.aura

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.Migrations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Migration test harness — each test uses its own uniquely-named database so
 * tests are fully isolated and can run in any order without file-lock conflicts.
 *
 * Isolation rules enforced here:
 *   1. Each test has its own DB name constant (TEST_DB_SCHEMA / _1_2 / _2_3).
 *   2. [tearDown] deletes all three DB files after every test so no version-
 *      mismatch state leaks between runs (SQLite refuses downgrade, which is
 *      how the flake manifested before this fix).
 *   3. The real Room instance opened in [v1_schema_opens] is registered with
 *      [helper] via [MigrationTestHelper.closeWhenFinished] instead of being
 *      closed manually — this guarantees cleanup even if an assertion throws.
 *   4. Migration tests wrap their [SupportSQLiteDatabase] in try-finally so
 *      [close] always runs regardless of assertion outcome.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    companion object {
        /** Unique DB names per test — prevents cross-test file-lock conflicts. */
        private const val TEST_DB_SCHEMA = "migration-test-schema"
        private const val TEST_DB_1_2    = "migration-test-1-to-2"
        private const val TEST_DB_2_3    = "migration-test-2-to-3"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /**
     * Delete all test DB files after each test so that no leftover file at a
     * higher schema version can prevent a subsequent test from re-creating the
     * DB at an earlier version.
     */
    @After
    fun tearDown() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf(TEST_DB_SCHEMA, TEST_DB_1_2, TEST_DB_2_3)) {
            ctx.deleteDatabase(name)
        }
    }

    @Test
    @Throws(IOException::class)
    fun v1_schema_opens() {
        // Create a fresh v1 database from the exported schema and close it.
        helper.createDatabase(TEST_DB_SCHEMA, 1).apply { close() }

        // Reopen via the real Room build; if migrations weren't wired
        // correctly Room would throw IllegalStateException here.
        // closeWhenFinished registers the database with the Rule so cleanup
        // is guaranteed even when assertions throw.
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB_SCHEMA
        )
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()
        helper.closeWhenFinished(db)
        assertNotNull(db.contactDao())
        assertNotNull(db.profileDao())
    }

    /**
     * PR-14: validate the 1→2 migration. Inserts a sample contact at v1,
     * runs the migration, and asserts:
     *   - existing rows survive untouched (no data loss)
     *   - the new blocked_endpoints table is now usable
     */
    @Test
    @Throws(IOException::class)
    fun migrate_1_to_2_preserves_data_and_creates_blocked_endpoints() {
        // Seed a v1 database with one contact.
        helper.createDatabase(TEST_DB_1_2, 1).use { v1 ->
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
            TEST_DB_1_2, 2, true, Migrations.MIGRATION_1_2
        )
        try {
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
        } finally {
            migrated.close()
        }
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
        helper.createDatabase(TEST_DB_2_3, 2).use { v2 ->
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
            TEST_DB_2_3, 3, true, Migrations.MIGRATION_2_3
        )
        try {
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
        } finally {
            migrated.close()
        }
    }
}
