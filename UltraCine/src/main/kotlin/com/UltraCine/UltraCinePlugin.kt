package com.UltraCine

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class UltraCineProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(UltraCine())
        registerExtractorAPI(EmbedPlayUpnsPro())
        registerExtractorAPI(EmbedPlayUpnOne())
    }
}

