package com.example.heartzonemonitor.ui.navigation

object NavDestinations {
    const val ONBOARDING_GRAPH = "onboarding_graph"
    const val MAIN_GRAPH = "main_graph"

    const val ONBOARDING_PROFILE = "onboarding/profile"
    const val ONBOARDING_PAIRING = "onboarding/pairing"

    const val LIVE_SESSION = "session/live"
    const val SESSION_HISTORY = "history"
    const val SESSION_DETAIL_ARG_ID = "sessionId"
    const val SESSION_DETAIL_ROUTE = "history/detail/{$SESSION_DETAIL_ARG_ID}"
    const val SETTINGS = "settings"
    const val DEVICES = "devices"

    fun sessionDetailRoute(sessionId: String): String =
        "history/detail/${android.net.Uri.encode(sessionId)}"
}
