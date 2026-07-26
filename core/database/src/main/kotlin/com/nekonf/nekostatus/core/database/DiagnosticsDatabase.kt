package com.nekonf.nekostatus.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "diagnostic_logs")
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val level: String,
    val category: String,
    val message: String,
)

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeLatest(limit: Int = 500): Flow<List<DiagnosticLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long)

    @Query("DELETE FROM diagnostic_logs")
    suspend fun clear()
}

@Database(entities = [DiagnosticLogEntity::class], version = 1, exportSchema = true)
abstract class DiagnosticsDatabase : RoomDatabase() {
    abstract fun logDao(): DiagnosticLogDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): DiagnosticsDatabase = Room.databaseBuilder(context, DiagnosticsDatabase::class.java, "neko-diagnostics.db").build()

    @Provides
    fun provideDiagnosticLogDao(database: DiagnosticsDatabase): DiagnosticLogDao = database.logDao()
}
