package one.only.player.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SeekMode {
    EXACT,
    CLOSEST_SYNC,
    PREVIOUS_SYNC,
    NEXT_SYNC,
}
