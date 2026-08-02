package dk.biomon.station

/**
 * Every detection is written to the SQLite outbox (Database.insert, then
 * Database.markDelivered per publisher) before any publisher runs — API.md §3. Today
 * there is exactly one [Publisher], [LocalPublisher], and its "delivery" is the write
 * that already happened. THE SEAM for a remote publisher later: implement this
 * interface, read `Database.pendingForPublisher("your-name")` on a retry loop, and mark
 * delivered on success. No change to the write path, the schema, or StationService's
 * capture/inference loop — that is the whole point of routing every insert through the
 * outbox now, while nothing is remote.
 */
interface Publisher {
    val name: String
    suspend fun publish(ids: List<Long>): Result<Unit>
}

/** The only publisher today. Detections are already durable in SQLite the moment
 *  Database.insert returns, so "publish" is just marking the outbox row delivered —
 *  there is no separate local sink to write to. */
class LocalPublisher(private val db: Database) : Publisher {
    override val name = "local"
    override suspend fun publish(ids: List<Long>): Result<Unit> {
        for (id in ids) db.markDelivered(id, name)
        return Result.success(Unit)
    }
}
