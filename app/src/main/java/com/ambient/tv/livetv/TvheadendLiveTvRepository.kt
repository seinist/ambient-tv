package com.ambient.tv.livetv

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.tvheadend.data.TvheadendRepository

class TvheadendLiveTvRepository(
    override val source: LiveTvSource,
    private val delegate: TvheadendRepository,
    private val preferences: AppPreferences,
    private val scope: String,
) : LiveTvRepository {
    private fun checkSource() = check(preferences.isTvheadendConfigured && preferences.tvheadendHistoryScope == scope) { "TV source configuration changed." }
    override fun authHeaders(): Map<String, String> { checkSource(); return delegate.authHeaders() }
    override suspend fun capabilities(force: Boolean): LiveTvCapabilities {
        checkSource()
        delegate.serverInfo(force)
        return LiveTvCapabilities(true, true, true, true, true, true, true)
    }
    override suspend fun channels(force: Boolean): List<LiveTvChannel> {
        checkSource(); return delegate.channels(force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun epgEvents(startEpochSeconds: Long, windowHours: Int, force: Boolean): List<LiveTvProgramme> {
        checkSource(); return delegate.epgEvents(startEpochSeconds, windowHours, force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun nowEvents(force: Boolean): List<LiveTvProgramme> {
        checkSource(); return delegate.nowEvents(force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun channelEpgEvents(channelId: String, startEpochSeconds: Long, windowHours: Int): List<LiveTvProgramme> {
        checkSource(); return delegate.epgEvents(startEpochSeconds, windowHours, channelId = channelId).map { it.copy(sourceId = source.id) }
    }
    override suspend fun finishedRecordings(force: Boolean): List<LiveTvRecording> {
        checkSource(); return delegate.finishedRecordings(force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun failedRecordings(force: Boolean): List<LiveTvRecording> {
        checkSource(); return delegate.failedRecordings(force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun upcomingRecordings(force: Boolean): List<LiveTvRecording> {
        checkSource(); return delegate.upcomingRecordings(force).map { it.copy(sourceId = source.id) }
    }
    override suspend fun autoRecordings(force: Boolean): List<LiveTvSeriesTimer> { checkSource(); return delegate.autoRecordings(force) }
    override suspend fun scheduleRecording(eventId: String): Result<Unit> { checkSource(); return delegate.scheduleRecording(eventId) }
    override suspend fun scheduleSeries(eventId: String): Result<Unit> { checkSource(); return delegate.scheduleSeries(eventId) }
    override suspend fun cancelRecording(uuid: String): Result<Unit> { checkSource(); return delegate.cancelRecording(uuid) }
    override suspend fun deleteRecording(uuid: String): Result<Unit> { checkSource(); return delegate.deleteRecording(uuid) }
    override suspend fun deleteAutoRecording(uuid: String): Result<Unit> { checkSource(); return delegate.deleteAutoRecording(uuid) }
    override suspend fun liveStream(channel: LiveTvChannel, profileOverride: String?): LiveTvPlaybackSource { checkSource(); return delegate.liveStream(channel, profileOverride) }
    override suspend fun recordingStream(entry: LiveTvRecording): LiveTvPlaybackSource { checkSource(); return delegate.recordingStream(entry) }
    override suspend fun streamProfiles(force: Boolean): List<LiveTvStreamProfile> { checkSource(); return delegate.streamProfiles(force) }
    override suspend fun invalidateAll() { checkSource(); delegate.invalidateAll() }
    override suspend fun dashboard(): LiveTvDashboard {
        checkSource()
        return delegate.dashboard().let { d -> d.copy(
            channels = d.channels.map { it.copy(sourceId = source.id) },
            events = d.events.map { it.copy(sourceId = source.id) },
            recordings = d.recordings.map { it.copy(sourceId = source.id) },
            upcoming = d.upcoming.map { it.copy(sourceId = source.id) },
            failedRecordings = d.failedRecordings.map { it.copy(sourceId = source.id) },
        ) }
    }
}
