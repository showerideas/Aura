package com.showerideas.aura

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.showerideas.aura.data.local.AppDatabase
import com.showerideas.aura.data.local.Migrations
import org.junit.Assert.assertNotNull
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
}
