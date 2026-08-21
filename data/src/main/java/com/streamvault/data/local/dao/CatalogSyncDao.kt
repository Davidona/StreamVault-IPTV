package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.CategoryImportStageEntity
import com.streamvault.data.local.entity.ChannelImportStageEntity
import com.streamvault.data.local.entity.MovieImportStageEntity
import com.streamvault.data.local.entity.SeriesImportStageEntity

data class ChannelStageCategorySummary(
  val categoryId: Long,
  val name: String,
  val isAdult: Boolean
)

@Dao
interface CatalogSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannelStages(rows: List<ChannelImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovieStages(rows: List<MovieImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeriesStages(rows: List<SeriesImportStageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategoryStages(rows: List<CategoryImportStageEntity>)

    @Query("DELETE FROM channel_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearChannelStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM movie_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearMovieStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM series_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearSeriesStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM category_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun clearCategoryStages(providerId: Long, sessionId: Long)

    @Query("DELETE FROM channel_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderChannelStages(providerId: Long)

    @Query("DELETE FROM movie_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderMovieStages(providerId: Long)

    @Query("DELETE FROM series_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderSeriesStages(providerId: Long)

    @Query("DELETE FROM category_import_stage WHERE provider_id = :providerId")
    suspend fun clearProviderCategoryStages(providerId: Long)

    @Query("SELECT * FROM category_import_stage WHERE provider_id = :providerId AND session_id = :sessionId AND type = :type")
    suspend fun getCategoryStages(providerId: Long, sessionId: Long, type: String): List<CategoryImportStageEntity>

    @Query("SELECT * FROM channel_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun getChannelStages(providerId: Long, sessionId: Long): List<ChannelImportStageEntity>

    @Query("SELECT * FROM movie_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun getMovieStages(providerId: Long, sessionId: Long): List<MovieImportStageEntity>

    @Query("SELECT * FROM series_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun getSeriesStages(providerId: Long, sessionId: Long): List<SeriesImportStageEntity>

    @Query("SELECT COUNT(*) FROM channel_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun countChannelStages(providerId: Long, sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM movie_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun countMovieStages(providerId: Long, sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM series_import_stage WHERE provider_id = :providerId AND session_id = :sessionId")
    suspend fun countSeriesStages(providerId: Long, sessionId: Long): Int

    @Query("SELECT COUNT(*) FROM category_import_stage WHERE provider_id = :providerId AND session_id = :sessionId AND type = :type")
    suspend fun countCategoryStages(providerId: Long, sessionId: Long, type: String): Int

    @Query(
      """
      SELECT
        category_id AS categoryId,
        COALESCE(
          MIN(CASE WHEN category_name IS NOT NULL AND TRIM(category_name) != '' THEN category_name END),
          'Category ' || category_id
        ) AS name,
        MAX(CASE WHEN is_adult THEN 1 ELSE 0 END) AS isAdult
      FROM channel_import_stage
      WHERE provider_id = :providerId
        AND session_id = :sessionId
        AND category_id IS NOT NULL
      GROUP BY category_id
      """
    )
    suspend fun getChannelStageCategorySummaries(
      providerId: Long,
      sessionId: Long
    ): List<ChannelStageCategorySummary>

    @Query(
        """
        UPDATE categories
        SET name = (
                SELECT stage.name
                FROM category_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.type = :type
                  AND stage.category_id = categories.category_id
            ),
            parent_id = (
                SELECT stage.parent_id
                FROM category_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.type = :type
                  AND stage.category_id = categories.category_id
            ),
            provider_order = (
                SELECT stage.provider_order
                FROM category_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.type = :type
                  AND stage.category_id = categories.category_id
            ),
            is_adult = (
                SELECT stage.is_adult
                FROM category_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.type = :type
                  AND stage.category_id = categories.category_id
            ),
            sync_fingerprint = (
                SELECT stage.sync_fingerprint
                FROM category_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.type = :type
                  AND stage.category_id = categories.category_id
            )
        WHERE provider_id = :providerId
          AND type = :type
          AND EXISTS (
              SELECT 1
              FROM category_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.type = :type
                AND stage.category_id = categories.category_id
                AND categories.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedCategoriesFromStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        INSERT INTO categories (
            category_id,
            name,
            parent_id,
            type,
            provider_id,
            provider_order,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.category_id,
            stage.name,
            stage.parent_id,
            stage.type,
            stage.provider_id,
            stage.provider_order,
            stage.is_adult,
            0,
            stage.sync_fingerprint
        FROM category_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND stage.type = :type
          AND NOT EXISTS (
              SELECT 1
              FROM categories AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.type = stage.type
                AND existing.category_id = stage.category_id
          )
        """
    )
    suspend fun insertMissingCategoriesFromStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        DELETE FROM categories
        WHERE provider_id = :providerId
          AND type = :type
          AND NOT EXISTS (
              SELECT 1
              FROM category_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.type = :type
                AND stage.category_id = categories.category_id
          )
        """
    )
    suspend fun deleteStaleCategoriesForStage(providerId: Long, sessionId: Long, type: String)

    @Query(
        """
        UPDATE channels
        SET name = (
                SELECT stage.name
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            logo_url = (
                SELECT stage.logo_url
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            group_title = (
                SELECT stage.group_title
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            category_id = (
                SELECT stage.category_id
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            category_name = (
                SELECT stage.category_name
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            stream_url = (
                SELECT stage.stream_url
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            epg_channel_id = (
                SELECT stage.epg_channel_id
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            number = (
                SELECT stage.number
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            catch_up_supported = (
                SELECT stage.catch_up_supported
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            catch_up_days = (
                SELECT stage.catch_up_days
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            catchUpSource = (
                SELECT stage.catchUpSource
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            logical_group_id = (
                SELECT stage.logical_group_id
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            error_count = (
                SELECT stage.error_count
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            is_adult = (
                SELECT stage.is_adult
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            ),
            sync_fingerprint = (
                SELECT stage.sync_fingerprint
                FROM channel_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = channels.stream_id
            )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM channel_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = channels.stream_id
                AND channels.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedChannelsFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO channels (
            stream_id,
            name,
            logo_url,
            group_title,
            category_id,
            category_name,
            stream_url,
            epg_channel_id,
            number,
            catch_up_supported,
            catch_up_days,
            catchUpSource,
            logical_group_id,
            error_count,
            provider_id,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.stream_id,
            stage.name,
            stage.logo_url,
            stage.group_title,
            stage.category_id,
            stage.category_name,
            stage.stream_url,
            stage.epg_channel_id,
            stage.number,
            stage.catch_up_supported,
            stage.catch_up_days,
            stage.catchUpSource,
            stage.logical_group_id,
            stage.error_count,
            stage.provider_id,
            stage.is_adult,
            CASE WHEN EXISTS (
                SELECT 1 FROM categories AS category
                WHERE category.provider_id = stage.provider_id
                  AND category.category_id = stage.category_id
                  AND category.type = 'LIVE'
                  AND category.is_user_protected = 1
            ) THEN 1 ELSE 0 END,
            stage.sync_fingerprint
        FROM channel_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM channels AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.stream_id = stage.stream_id
          )
        """
    )
    suspend fun insertMissingChannelsFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM channels
        WHERE provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM channel_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = channels.stream_id
          )
        """
    )
    suspend fun deleteStaleChannelsForStage(providerId: Long, sessionId: Long)

    @Query(
        """
        UPDATE movies
        SET name = (
                SELECT stage.name
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            poster_url = (
                SELECT stage.poster_url
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            backdrop_url = (
                SELECT stage.backdrop_url
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            category_id = (
                SELECT stage.category_id
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            category_name = (
                SELECT stage.category_name
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            stream_url = (
                SELECT stage.stream_url
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            container_extension = (
                SELECT stage.container_extension
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            plot = (
                SELECT stage.plot
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            cast = (
                SELECT stage.cast
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            director = (
                SELECT stage.director
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            genre = (
                SELECT stage.genre
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            release_date = (
                SELECT stage.release_date
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            duration = (
                SELECT stage.duration
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            duration_seconds = (
                SELECT stage.duration_seconds
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            rating = (
                SELECT stage.rating
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            year = (
                SELECT stage.year
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            tmdb_id = (
                SELECT stage.tmdb_id
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            youtube_trailer = (
                SELECT stage.youtube_trailer
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            is_adult = (
                SELECT stage.is_adult
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            ),
            sync_fingerprint = (
                SELECT stage.sync_fingerprint
                FROM movie_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.stream_id = movies.stream_id
            )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM movie_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = movies.stream_id
                AND movies.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedMoviesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO movies (
            stream_id,
            name,
            poster_url,
            backdrop_url,
            category_id,
            category_name,
            stream_url,
            container_extension,
            plot,
            cast,
            director,
            genre,
            release_date,
            duration,
            duration_seconds,
            rating,
            year,
            tmdb_id,
            youtube_trailer,
            provider_id,
            watch_progress,
            watch_count,
            last_watched_at,
            is_adult,
            is_user_protected,
            sync_fingerprint,
            added_at
        )
        SELECT
            stage.stream_id,
            stage.name,
            stage.poster_url,
            stage.backdrop_url,
            stage.category_id,
            stage.category_name,
            stage.stream_url,
            stage.container_extension,
            stage.plot,
            stage.cast,
            stage.director,
            stage.genre,
            stage.release_date,
            stage.duration,
            stage.duration_seconds,
            stage.rating,
            stage.year,
            stage.tmdb_id,
            stage.youtube_trailer,
            stage.provider_id,
            0,
            0,
            0,
            stage.is_adult,
            CASE WHEN EXISTS (
                SELECT 1 FROM categories AS category
                WHERE category.provider_id = stage.provider_id
                  AND category.category_id = stage.category_id
                  AND category.type IN ('MOVIE', 'VOD')
                  AND category.is_user_protected = 1
            ) THEN 1 ELSE 0 END,
            stage.sync_fingerprint,
            stage.added_at
        FROM movie_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM movies AS existing
              WHERE existing.provider_id = stage.provider_id
                AND existing.stream_id = stage.stream_id
          )
        """
    )
    suspend fun insertMissingMoviesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM movies
        WHERE provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM movie_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.stream_id = movies.stream_id
          )
        """
    )
    suspend fun deleteStaleMoviesForStage(providerId: Long, sessionId: Long)

    @Query(
        """
        UPDATE series
        SET name = (
                SELECT stage.name
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            poster_url = (
                SELECT stage.poster_url
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            backdrop_url = (
                SELECT stage.backdrop_url
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            category_id = (
                SELECT stage.category_id
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            category_name = (
                SELECT stage.category_name
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            plot = (
                SELECT stage.plot
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            cast = (
                SELECT stage.cast
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            director = (
                SELECT stage.director
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            genre = (
                SELECT stage.genre
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            release_date = (
                SELECT stage.release_date
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            rating = (
                SELECT stage.rating
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            tmdb_id = (
                SELECT stage.tmdb_id
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            youtube_trailer = (
                SELECT stage.youtube_trailer
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            episode_run_time = (
                SELECT stage.episode_run_time
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            last_modified = (
                SELECT stage.last_modified
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            provider_series_id = (
                SELECT stage.provider_series_id
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            is_adult = (
                SELECT stage.is_adult
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            ),
            sync_fingerprint = (
                SELECT stage.sync_fingerprint
                FROM series_import_stage AS stage
                WHERE stage.session_id = :sessionId
                  AND stage.provider_id = :providerId
                  AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
            )
        WHERE provider_id = :providerId
          AND EXISTS (
              SELECT 1
              FROM series_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
                AND series.sync_fingerprint != stage.sync_fingerprint
          )
        """
    )
    suspend fun updateChangedSeriesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        INSERT INTO series (
            series_id,
            provider_series_id,
            name,
            poster_url,
            backdrop_url,
            category_id,
            category_name,
            plot,
            cast,
            director,
            genre,
            release_date,
            rating,
            tmdb_id,
            youtube_trailer,
            episode_run_time,
            last_modified,
            provider_id,
            is_adult,
            is_user_protected,
            sync_fingerprint
        )
        SELECT
            stage.series_id,
            stage.provider_series_id,
            stage.name,
            stage.poster_url,
            stage.backdrop_url,
            stage.category_id,
            stage.category_name,
            stage.plot,
            stage.cast,
            stage.director,
            stage.genre,
            stage.release_date,
            stage.rating,
            stage.tmdb_id,
            stage.youtube_trailer,
            stage.episode_run_time,
            stage.last_modified,
            stage.provider_id,
            stage.is_adult,
            CASE WHEN EXISTS (
                SELECT 1 FROM categories AS category
                WHERE category.provider_id = stage.provider_id
                  AND category.category_id = stage.category_id
                  AND category.type = 'SERIES'
                  AND category.is_user_protected = 1
            ) THEN 1 ELSE 0 END,
            stage.sync_fingerprint
        FROM series_import_stage AS stage
        WHERE stage.session_id = :sessionId
          AND stage.provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM series AS existing
              WHERE existing.provider_id = stage.provider_id
                AND COALESCE(NULLIF(existing.provider_series_id, ''), CAST(existing.series_id AS TEXT)) = stage.provider_series_key
          )
        """
    )
    suspend fun insertMissingSeriesFromStage(providerId: Long, sessionId: Long)

    @Query(
        """
        DELETE FROM series
        WHERE provider_id = :providerId
          AND NOT EXISTS (
              SELECT 1
              FROM series_import_stage AS stage
              WHERE stage.session_id = :sessionId
                AND stage.provider_id = :providerId
                AND stage.provider_series_key = COALESCE(NULLIF(series.provider_series_id, ''), CAST(series.series_id AS TEXT))
          )
        """
    )
    suspend fun deleteStaleSeriesForStage(providerId: Long, sessionId: Long)

    // ── FTS bulk rebuild (call once after each bulk catalog write, outside the transaction) ──

    @Query("INSERT INTO channels_fts(channels_fts) VALUES('rebuild')")
    suspend fun rebuildChannelFts()

    @Query("INSERT INTO movies_fts(movies_fts) VALUES('rebuild')")
    suspend fun rebuildMovieFts()

    @Query("INSERT INTO series_fts(series_fts) VALUES('rebuild')")
    suspend fun rebuildSeriesFts()
}
