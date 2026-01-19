package com.CineAgora

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineAgoraPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineAgora())
    }
}
