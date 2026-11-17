package one.only.player.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.only.player.core.model.AudioPlaybackSpeedMode
import one.only.player.core.model.pitchFor
import one.only.player.feature.player.service.isSkipSilenceEnabled
import one.only.player.feature.player.service.setSkipSilenceEnabled
import one.only.player.feature.player.service.setTransientPlaybackSpeed

@UnstableApi
@Composable
fun rememberPlaybackParametersState(
    player: Player,
    audioPlaybackSpeedMode: AudioPlaybackSpeedMode = AudioPlaybackSpeedMode.MEDIA3_SONIC,
): PlaybackParametersState {
    val scope = rememberCoroutineScope()
    val playbackParametersState = remember(player, audioPlaybackSpeedMode) {
        PlaybackParametersState(player, scope, audioPlaybackSpeedMode)
    }
    LaunchedEffect(player, audioPlaybackSpeedMode) { playbackParametersState.observe() }
    return playbackParametersState
}

@UnstableApi
class PlaybackParametersState(
    private val player: Player,
    private val scope: CoroutineScope,
    private val audioPlaybackSpeedMode: AudioPlaybackSpeedMode = AudioPlaybackSpeedMode.MEDIA3_SONIC,
) {
    var speed: Float by mutableFloatStateOf(1f)
        private set

    var isSkipSilenceEnabled: Boolean by mutableStateOf(false)
        private set

    fun setPlaybackSpeed(speed: Float) {
        val pitch = audioPlaybackSpeedMode.pitchFor(speed)
        when (player) {
            is MediaController -> player.setTransientPlaybackSpeed(speed, pitch)
            else -> player.setPlaybackParameters(PlaybackParameters(speed, pitch))
        }
    }

    fun setIsSkipSilenceEnabled(isEnabled: Boolean) {
        scope.launch {
            when (player) {
                is MediaController -> player.setSkipSilenceEnabled(isEnabled)
                is ExoPlayer -> player.skipSilenceEnabled = isEnabled
                else -> return@launch
            }
            updateSkipSilenceEnabled()
        }
    }

    suspend fun observe() {
        updateSpeed()
        updateSkipSilenceEnabled()

        player.listen { events ->
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                updateSpeed()
            }
        }
    }

    private fun updateSpeed() {
        speed = player.playbackParameters.speed
    }

    private fun updateSkipSilenceEnabled() {
        scope.launch {
            isSkipSilenceEnabled = when (player) {
                is MediaController -> player.isSkipSilenceEnabled()
                is ExoPlayer -> player.skipSilenceEnabled
                else -> return@launch
            }
        }
    }
}
