package one.only.player.feature.player.service.playback

import android.media.MediaFormat
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.io.IOException
import one.only.player.core.common.Logger
import one.only.player.core.model.VideoOutputMode
import one.only.player.feature.player.extensions.diagnostics
import one.only.player.feature.player.extensions.toLogString
import one.only.player.feature.player.service.decoder.VideoFrameTimestampAdjustment
import one.only.player.feature.player.service.effects.VideoEffectsCoordinator

@UnstableApi
internal class PlaybackStartupAnalyticsListener(
    private val tag: String,
    private val currentPlayerProvider: () -> ExoPlayer?,
    private val videoEffectsCoordinator: VideoEffectsCoordinator,
    private val videoOutputModeProvider: () -> VideoOutputMode,
    private val frameTimestampAdjustment: VideoFrameTimestampAdjustment,
) : AnalyticsListener,
    VideoFrameMetadataListener {

    private var startupTimestamp = 0L
    private var bufferingStartedAt = 0L
    private var stallCount = 0
    private var totalStallDurationMs = 0L
    private var hasReachedReady = false
    private var isCurrentBufferingStall = false
    private var seekSequence = 0L
    private var pendingSeek: PendingSeek? = null
    private var bufferingStartedAtElapsedMs = 0L

    private data class PendingSeek(
        val id: Long,
        val startedAtMs: Long,
        val fromPositionMs: Long,
        val targetPositionMs: Long,
        val outputPath: VideoOutputMode,
        val bufferingStartedAtMs: Long? = null,
        val readyAtMs: Long? = null,
        val readyAtSeekStart: Boolean = false,
        val frameScheduledAtMs: Long? = null,
        val frameRenderedAtMs: Long? = null,
    )

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return

        val previousSeek = pendingSeek
        if (previousSeek != null) {
            Logger.info(
                tag,
                "seek superseded id=${previousSeek.id} target=${previousSeek.targetPositionMs} " +
                    "ready=${previousSeek.readyAtMs != null} scheduled=${previousSeek.frameScheduledAtMs != null} " +
                    "rendered=${previousSeek.frameRenderedAtMs != null}",
            )
        }

        val startedAtMs = SystemClock.elapsedRealtime()
        val player = currentPlayerProvider()
        val outputPath = videoOutputModeProvider()
        val seek = PendingSeek(
            id = ++seekSequence,
            startedAtMs = startedAtMs,
            fromPositionMs = oldPosition.positionMs,
            targetPositionMs = newPosition.positionMs,
            outputPath = outputPath,
            bufferingStartedAtMs = bufferingStartedAtElapsedMs.takeIf { it > 0L },
            readyAtMs = startedAtMs.takeIf { player?.playbackState == Player.STATE_READY },
            readyAtSeekStart = player?.playbackState == Player.STATE_READY,
        )
        pendingSeek = seek

        val bufferedAheadMs = player?.let {
            (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L)
        } ?: 0L
        Logger.info(
            tag,
            "seek start id=${seek.id} outputPath=${seek.outputPath.name} " +
                "from=${seek.fromPositionMs} target=${seek.targetPositionMs} " +
                "seekable=${player?.isCurrentMediaItemSeekable} bufferedAheadMs=$bufferedAheadMs " +
                "readyAtSeekStart=${seek.readyAtSeekStart} bufferingAlreadyActive=${seek.bufferingStartedAtMs != null} " +
                "${diagnosticSummary()}",
        )
        if (seek.readyAtMs != null) logSeekReady(seek)
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        startupTimestamp = System.currentTimeMillis()
        bufferingStartedAt = 0L
        bufferingStartedAtElapsedMs = 0L
        frameTimestampAdjustment.offsetUs = null
        stallCount = 0
        totalStallDurationMs = 0L
        hasReachedReady = false
        isCurrentBufferingStall = false
        pendingSeek = null
        Logger.info(tag, "startup mediaItem reason=$reason")
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        if (state == Player.STATE_BUFFERING) {
            val bufferingAtMs = SystemClock.elapsedRealtime()
            if (bufferingStartedAt == 0L) {
                bufferingStartedAt = System.currentTimeMillis()
                bufferingStartedAtElapsedMs = bufferingAtMs
                isCurrentBufferingStall = hasReachedReady && currentPlayerProvider()?.playWhenReady == true
            }
            pendingSeek?.let { seek ->
                val resetReadyAt = seek.readyAtSeekStart &&
                    seek.frameScheduledAtMs == null &&
                    seek.frameRenderedAtMs == null
                val updatedSeek = seek.copy(
                    bufferingStartedAtMs = seek.bufferingStartedAtMs ?: bufferingStartedAtElapsedMs.takeIf { it > 0L },
                    readyAtMs = if (resetReadyAt) null else seek.readyAtMs,
                    readyAtSeekStart = if (resetReadyAt) false else seek.readyAtSeekStart,
                )
                pendingSeek = updatedSeek
                if (resetReadyAt) {
                    Logger.info(tag, "seek readiness invalidated id=${seek.id} reason=buffering")
                }
            }
            if (startupTimestamp == 0L) startupTimestamp = System.currentTimeMillis()
        } else if (bufferingStartedAt != 0L) {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartedAt
            if (isCurrentBufferingStall) {
                stallCount++
                totalStallDurationMs += bufferingDuration
            }
            Logger.info(
                tag,
                "startup bufferingEnd durationMs=$bufferingDuration isStall=$isCurrentBufferingStall stallCount=$stallCount " +
                    "totalStallDurationMs=$totalStallDurationMs ${diagnosticSummary()}",
            )
            bufferingStartedAt = 0L
            bufferingStartedAtElapsedMs = 0L
            isCurrentBufferingStall = false
        }

        if (state == Player.STATE_READY) {
            hasReachedReady = true
            pendingSeek?.let { seek ->
                if (seek.readyAtMs == null) {
                    val readyAtMs = SystemClock.elapsedRealtime()
                    val updatedSeek = seek.copy(
                        readyAtMs = readyAtMs,
                        readyAtSeekStart = false,
                    )
                    pendingSeek = updatedSeek
                    logSeekReady(updatedSeek)
                    logSeekTimingIfComplete(updatedSeek)
                }
            }
        }

        val label = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
        Logger.info(tag, "startup state=$label t=${elapsed()}ms ${diagnosticSummary()}")
    }

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        Logger.info(
            tag,
            "startup loadStart t=${elapsed()}ms type=${mediaLoadData.dataType} ${diagnosticSummary()}",
        )
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Logger.info(
            tag,
            "startup loadDone t=${elapsed()}ms type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded} ${diagnosticSummary()}",
        )
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        Logger.info(
            tag,
            "startup loadCanceled t=${elapsed()}ms type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded} ${diagnosticSummary()}",
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        Logger.error(
            tag,
            "startup loadError t=${elapsed()}ms type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded} " +
                "wasCanceled=$wasCanceled ${diagnosticSummary()}",
            error,
        )
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        val seek = pendingSeek ?: return
        if (seek.frameScheduledAtMs != null) return

        val frameScheduledAtMs = SystemClock.elapsedRealtime()
        val timestampAdjustmentUs = when (seek.outputPath) {
            VideoOutputMode.DIRECT -> 0L
            VideoOutputMode.EFFECTS -> frameTimestampAdjustment.offsetUs
        }
        val mediaPresentationTimeUs = timestampAdjustmentUs?.let { presentationTimeUs - it }
        val graphPresentationTimeMs = presentationTimeUs / 1_000L
        val mediaPresentationTimeMs = mediaPresentationTimeUs?.div(1_000L)
        val frameOffsetMs = mediaPresentationTimeMs?.minus(seek.targetPositionMs)
        val releaseInMs = if (releaseTimeNs != C.TIME_UNSET) {
            (releaseTimeNs - System.nanoTime()) / 1_000_000L
        } else {
            null
        }
        var updatedSeek = seek.copy(frameScheduledAtMs = frameScheduledAtMs)
        if (updatedSeek.readyAtMs == null && currentPlayerProvider()?.playbackState == Player.STATE_READY) {
            updatedSeek = updatedSeek.copy(readyAtMs = frameScheduledAtMs)
        }
        pendingSeek = updatedSeek
        Logger.info(
            tag,
            "seek firstVideoFrame id=${seek.id} outputPath=${seek.outputPath.name} " +
                "seekToFrameScheduledMs=${frameScheduledAtMs - seek.startedAtMs} " +
                "readyToFrameScheduledMs=${updatedSeek.readyAtMs?.let { frameScheduledAtMs - it }} " +
                "framePtsGraphMs=$graphPresentationTimeMs " +
                "frameTimestampAdjustmentMs=${timestampAdjustmentUs?.div(1_000L) ?: "unknown"} " +
                "framePtsMs=${mediaPresentationTimeMs ?: "unknown"} target=${seek.targetPositionMs} " +
                "frameOffsetMs=${frameOffsetMs ?: "unknown"} releaseInMs=$releaseInMs",
        )
        logSeekTimingIfComplete(updatedSeek)
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        pendingSeek?.let { seek ->
            if (seek.frameRenderedAtMs == null) {
                val frameRenderedAtMs = SystemClock.elapsedRealtime()
                var updatedSeek = seek.copy(frameRenderedAtMs = frameRenderedAtMs)
                if (updatedSeek.readyAtMs == null && currentPlayerProvider()?.playbackState == Player.STATE_READY) {
                    updatedSeek = updatedSeek.copy(readyAtMs = frameRenderedAtMs)
                }
                pendingSeek = updatedSeek
                Logger.info(
                    tag,
                    "seek firstFrameRendered id=${seek.id} outputPath=${seek.outputPath.name} " +
                        "seekToFrameRenderedMs=${frameRenderedAtMs - seek.startedAtMs} " +
                        "readyToFrameRenderedMs=${updatedSeek.readyAtMs?.let { frameRenderedAtMs - it }} " +
                        "frameScheduledToRenderedMs=${updatedSeek.frameScheduledAtMs?.let { frameRenderedAtMs - it }}",
                )
                logSeekTimingIfComplete(updatedSeek)
            }
        }
        Logger.info(tag, "startup firstFrame t=${elapsed()}ms ${diagnosticSummary()}")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Logger.info(
            tag,
            "startup audioUnderrun bufferSize=$bufferSize bufferSizeMs=$bufferSizeMs " +
                "elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs ${diagnosticSummary()}",
        )
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        Logger.info(
            tag,
            "startup droppedVideoFrames count=$droppedFrames elapsedMs=$elapsedMs ${diagnosticSummary()}",
        )
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoEffectsCoordinator.setDecoderName(decoderName)
        Logger.info(tag, "startup decoderInit=$decoderName dur=${initializationDurationMs}ms t=${elapsed()}ms")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoEffectsCoordinator.onVideoInputFormatChanged(
            player = currentPlayerProvider(),
            format = format,
        )
        Logger.info(
            tag,
            "startup videoFormat mime=${format.sampleMimeType} codecs=${format.codecs} bitrate=${format.bitrate} " +
                "transfer=${format.colorInfo?.colorTransfer} " +
                "standard=${format.colorInfo?.colorSpace} range=${format.colorInfo?.colorRange}",
        )
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Logger.info(tag, "startup audioFormat bitrate=${format.bitrate} mime=${format.sampleMimeType}")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Logger.info(tag, "startup audioDecoder=$decoderName dur=${initializationDurationMs}ms t=${elapsed()}ms")
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        val player = currentPlayerProvider()
        Logger.info(
            tag,
            "startup tracksChanged t=${elapsed()}ms groups=${tracks.groups.size} seekable=${player?.isCurrentMediaItemSeekable} " +
                "duration=${player?.duration} ${diagnosticSummary()}",
        )
    }

    private fun logSeekReady(seek: PendingSeek) {
        val readyAtMs = seek.readyAtMs ?: return
        val bufferingStartedAtMs = seek.bufferingStartedAtMs
        val bufferingMs = bufferingStartedAtMs?.let {
            readyAtMs - maxOf(it, seek.startedAtMs)
        } ?: 0L
        Logger.info(
            tag,
            "seek ready id=${seek.id} outputPath=${seek.outputPath.name} " +
                "seekToReadyMs=${readyAtMs - seek.startedAtMs} bufferingMs=$bufferingMs " +
                "bufferingStartedBeforeSeek=${bufferingStartedAtMs != null && bufferingStartedAtMs < seek.startedAtMs} " +
                "target=${seek.targetPositionMs}",
        )
    }

    private fun logSeekTimingIfComplete(seek: PendingSeek) {
        val readyAtMs = seek.readyAtMs ?: return
        val frameScheduledAtMs = seek.frameScheduledAtMs ?: return
        val frameRenderedAtMs = seek.frameRenderedAtMs ?: return
        val bufferingMs = seek.bufferingStartedAtMs?.let {
            readyAtMs - maxOf(it, seek.startedAtMs)
        } ?: 0L
        Logger.info(
            tag,
            "seek timing id=${seek.id} outputPath=${seek.outputPath.name} " +
                "seekToReadyMs=${readyAtMs - seek.startedAtMs} bufferingMs=$bufferingMs " +
                "seekToFrameScheduledMs=${frameScheduledAtMs - seek.startedAtMs} " +
                "readyToFrameScheduledMs=${frameScheduledAtMs - readyAtMs} " +
                "seekToFrameRenderedMs=${frameRenderedAtMs - seek.startedAtMs} " +
                "readyToFrameRenderedMs=${frameRenderedAtMs - readyAtMs} " +
                "frameScheduledToRenderedMs=${frameRenderedAtMs - frameScheduledAtMs} " +
                "frameScheduledBeforeReady=${frameScheduledAtMs < readyAtMs}",
        )
        if (pendingSeek?.id == seek.id) pendingSeek = null
    }

    private fun elapsed(): Long = startupTimestamp
        .takeIf { it != 0L }
        ?.let { System.currentTimeMillis() - it }
        ?: 0L

    private fun diagnosticSummary(): String = currentPlayerProvider()?.diagnostics()?.toLogString().orEmpty()

    fun currentStallMetrics(): StallMetrics {
        val currentDurationMs = if (isCurrentBufferingStall) {
            System.currentTimeMillis() - bufferingStartedAt
        } else {
            0L
        }
        return StallMetrics(
            count = stallCount,
            currentDurationMs = currentDurationMs,
            totalDurationMs = totalStallDurationMs,
        )
    }
}

internal data class StallMetrics(
    val count: Int,
    val currentDurationMs: Long,
    val totalDurationMs: Long,
)
