package app.etracker.tracker.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable queue of fixes waiting to be sent. Fixes are written here first and
 * only deleted once the server has accepted them, so a dead zone, a crash or a
 * reboot never loses a position.
 */
class FixQueue private constructor(context: Context) :
    SQLiteOpenHelper(context, "fixes.db", null, 1) {

    class Row(val id: Long, val fix: Fix)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE fixes (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "lat REAL NOT NULL, lon REAL NOT NULL, acc REAL, speed REAL, heading REAL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}

    @Synchronized
    fun add(f: Fix) {
        val db = writableDatabase
        db.insert(
            "fixes", null,
            ContentValues().apply {
                put("ts", f.ts)
                put("lat", f.lat)
                put("lon", f.lon)
                put("acc", f.accuracy?.toDouble())
                put("speed", f.speed?.toDouble())
                put("heading", f.heading?.toDouble())
            },
        )
        // cap the backlog: drop the oldest beyond MAX_QUEUED
        db.execSQL(
            "DELETE FROM fixes WHERE id IN (SELECT id FROM fixes ORDER BY id DESC LIMIT -1 OFFSET $MAX_QUEUED)",
        )
    }

    /** Oldest first, so the server sees them in order. */
    @Synchronized
    fun peek(limit: Int): List<Row> {
        val out = ArrayList<Row>()
        readableDatabase.rawQuery(
            "SELECT id, ts, lat, lon, acc, speed, heading FROM fixes ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += Row(
                    c.getLong(0),
                    Fix(
                        ts = c.getLong(1),
                        lat = c.getDouble(2),
                        lon = c.getDouble(3),
                        accuracy = if (c.isNull(4)) null else c.getFloat(4),
                        speed = if (c.isNull(5)) null else c.getFloat(5),
                        heading = if (c.isNull(6)) null else c.getFloat(6),
                    ),
                )
            }
        }
        return out
    }

    @Synchronized
    fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM fixes WHERE id IN (${ids.joinToString(",")})")
    }

    @Synchronized
    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fixes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    fun clear() {
        writableDatabase.execSQL("DELETE FROM fixes")
    }

    companion object {
        const val MAX_QUEUED = 20_000

        @Volatile private var instance: FixQueue? = null

        fun get(context: Context): FixQueue =
            instance ?: synchronized(this) {
                instance ?: FixQueue(context.applicationContext).also { instance = it }
            }
    }
}
