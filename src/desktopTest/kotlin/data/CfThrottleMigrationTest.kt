package data

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Deliberately connects its own throwaway H2 file (never touches AppDatabase.init()/the real
// ~/.zarchive DB, same technique as SavedResultQuantityRoundTripTest) to empirically verify that an
// existing install's cf_throttle_rules table -- created before tier_medium existed -- backfills a
// sane default instead of crashing on the first search after updating.
class CfThrottleMigrationTest {

    // Mirrors CfThrottleRules as it looked before tier_medium (the 3-category governance system)
    // existed -- same Exposed Table DSL the real old schema was actually created with.
    private object OldCfThrottleRules : org.jetbrains.exposed.sql.Table("cf_throttle_rules") {
        val baseUrl       = text("base_url")
        val cardThreshold = integer("card_threshold")
        val tier          = integer("tier")
        val tierLarge     = integer("tier_large").default(2)
        val lastHitAt     = long("last_hit_at")
        val lastHitCards  = integer("last_hit_cards")
        override val primaryKey = PrimaryKey(baseUrl)
    }

    @Test
    fun `pre-existing rows backfill tierMedium to its default after migrating`() = runBlocking {
        val tempDb = File.createTempFile("zarchive-test-cf-migration-", "")
        tempDb.delete()
        Database.connect("jdbc:h2:file:${tempDb.absolutePath};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")

        // Simulate an existing install: the old 2-bucket table, with a row already in it (e.g. a
        // store escalated from a prior 429 before this update shipped).
        transaction {
            SchemaUtils.create(OldCfThrottleRules)
            OldCfThrottleRules.insert {
                it[baseUrl] = "https://example.co.za"
                it[cardThreshold] = 300
                it[tier] = 2
                it[tierLarge] = 3
                it[lastHitAt] = 1_000L
                it[lastHitCards] = 12
            }
        }

        // Now run the app's real startup migration against that pre-existing table.
        transaction { SchemaUtils.createMissingTablesAndColumns(CfThrottleRules) }

        transaction {
            val row = CfThrottleRules.selectAll().first()
            assertEquals("https://example.co.za", row[CfThrottleRules.baseUrl])
            assertEquals(2, row[CfThrottleRules.tier])       // untouched by the migration
            assertEquals(3, row[CfThrottleRules.tierLarge])  // untouched by the migration
            assertEquals(2, row[CfThrottleRules.tierMedium]) // backfilled to its column default
        }
    }
}
