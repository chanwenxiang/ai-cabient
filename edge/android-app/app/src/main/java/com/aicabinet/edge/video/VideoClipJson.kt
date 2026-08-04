package com.aicabinet.edge.video

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object VideoClipJson {
    private val mapper = jacksonObjectMapper()

    fun build(clips: List<Pair<String, String>>): String {
        val now = System.currentTimeMillis()
        val payload = clips.map { (camera, uri) ->
            mapOf(
                "camera" to camera,
                "videoUri" to uri,
                "capturedAt" to now
            )
        }
        return mapper.writeValueAsString(payload)
    }
}
