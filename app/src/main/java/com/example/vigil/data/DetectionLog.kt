package com.example.vigil.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "detection_logs")
data class DetectionLog(
    @PrimaryKey val id: Long = System.currentTimeMillis(),
    val label: String,
    val classId: Int,
    val confidence: Float,
    // Flattened Bounding Box coordinates to prevent complex type conversions
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null,
    val thumbnailPath: String? = null,
    val videoPath: String? = null,
    val isPerson: Boolean = false,
    val isVehicle: Boolean = false,
    val speedMph: Int = 0,
    val direction: String = "",
    val personId: String = "",
    val plateText: String = ""
) {
    val timestampFormatted: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    val timeOnly: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

@Dao
interface DetectionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DetectionLog)

    @Query("SELECT * FROM detection_logs ORDER BY timestamp DESC LIMIT :count")
    suspend fun getRecentLogs(count: Int): List<DetectionLog>

    @Query("DELETE FROM detection_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM detection_logs")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM detection_logs WHERE isPerson = 1")
    suspend fun getPersonCount(): Int

    @Query("SELECT COUNT(*) FROM detection_logs WHERE isVehicle = 1")
    suspend fun getVehicleCount(): Int
}

@Database(entities = [DetectionLog::class], version = 1, exportSchema = false)
abstract class VigilDatabase : RoomDatabase() {
    abstract fun detectionLogDao(): DetectionLogDao

    companion object {
        @Volatile
        private var INSTANCE: VigilDatabase? = null

        fun getDatabase(context: Context): VigilDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VigilDatabase::class.java,
                    "vigil_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}