package com.aicabinet.edge.video

import java.io.File

data class VideoClipFile(
    val camera: String,
    val file: File
)

data class RecordingResult(
    val sessionId: String,
    val clips: List<VideoClipFile>
) {
    val fusionMode: String = if (clips.size >= 2) "MULTI" else "SINGLE"
    val primaryFile: File? = clips.firstOrNull()?.file
}
