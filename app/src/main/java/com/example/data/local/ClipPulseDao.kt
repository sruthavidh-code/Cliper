package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipPulseDao {
    @Query("SELECT * FROM video_projects ORDER BY createdAtMs DESC")
    fun observeAllProjects(): Flow<List<VideoProjectEntity>>

    @Query("SELECT * FROM video_projects WHERE id = :projectId LIMIT 1")
    fun observeProject(projectId: Long): Flow<VideoProjectEntity?>

    @Query("SELECT * FROM video_projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: Long): VideoProjectEntity?

    @Query("SELECT * FROM video_projects WHERE isDemo = 1 LIMIT 1")
    suspend fun getDemoProject(): VideoProjectEntity?

    @Query("SELECT COUNT(*) FROM viral_clips")
    fun observeTotalClipCount(): Flow<Int>

    @Query("SELECT * FROM viral_clips WHERE projectId = :projectId ORDER BY viralityScore DESC")
    fun observeClipsForProject(projectId: Long): Flow<List<ViralClipEntity>>

    @Query("SELECT * FROM viral_clips WHERE id = :clipId LIMIT 1")
    fun observeClipById(clipId: Long): Flow<ViralClipEntity?>

    @Query("SELECT * FROM viral_clips WHERE id = :clipId LIMIT 1")
    suspend fun getClipById(clipId: Long): ViralClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: VideoProjectEntity): Long

    @Update
    suspend fun updateProject(project: VideoProjectEntity)

    @Query("DELETE FROM video_projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClips(clips: List<ViralClipEntity>)

    @Update
    suspend fun updateClip(clip: ViralClipEntity)

    @Query("DELETE FROM viral_clips WHERE projectId = :projectId")
    suspend fun deleteClipsForProject(projectId: Long)
}
