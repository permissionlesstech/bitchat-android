package com.bitchat.android.model

data class FestivalChannelInfo(
    val name: String,
    val description: String
)

object FestivalChannels {
    const val GENERAL = "General"
    const val MAIN_STAGE = "Main Stage"
    const val FOOD_COURT = "Food Court"
    const val LOST_AND_FOUND = "Lost & Found"
    const val MEDICAL = "Medical"

    val CHANNELS = listOf(
        FestivalChannelInfo(GENERAL, "Festival-wide conversation"),
        FestivalChannelInfo(MAIN_STAGE, "Events and stage updates"),
        FestivalChannelInfo(FOOD_COURT, "Food stalls and food-related updates"),
        FestivalChannelInfo(LOST_AND_FOUND, "Lost and found items"),
        FestivalChannelInfo(MEDICAL, "Medical assistance and information")
    )

    val ALL_CHANNELS = CHANNELS.map { it.name }

    fun getDescription(channelName: String): String {
        return CHANNELS.firstOrNull { it.name.equals(channelName, ignoreCase = true) }?.description
            ?: "Festival channel"
    }
}
