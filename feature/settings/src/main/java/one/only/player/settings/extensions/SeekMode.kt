package one.only.player.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import one.only.player.core.model.SeekMode
import one.only.player.core.ui.R

@Composable
fun SeekMode.name(): String {
    val stringRes = when (this) {
        SeekMode.EXACT -> R.string.seek_mode_exact
        SeekMode.CLOSEST_SYNC -> R.string.seek_mode_closest_sync
        SeekMode.PREVIOUS_SYNC -> R.string.seek_mode_previous_sync
        SeekMode.NEXT_SYNC -> R.string.seek_mode_next_sync
    }

    return stringResource(id = stringRes)
}
