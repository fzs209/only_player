package one.only.player.feature.player.extensions

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import one.only.player.core.model.SeekMode

@OptIn(UnstableApi::class)
fun SeekMode.toSeekParameters(): SeekParameters = when (this) {
    SeekMode.EXACT -> SeekParameters.EXACT
    SeekMode.CLOSEST_SYNC -> SeekParameters.CLOSEST_SYNC
    SeekMode.PREVIOUS_SYNC -> SeekParameters.PREVIOUS_SYNC
    SeekMode.NEXT_SYNC -> SeekParameters.NEXT_SYNC
}
