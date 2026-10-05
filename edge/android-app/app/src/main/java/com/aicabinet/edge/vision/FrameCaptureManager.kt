package com.aicabinet.edge.vision

import android.graphics.Bitmap

/** 从关门录像抽开/关关键帧。无视频时返回 null，由调用方跳过本地推理。 */
class FrameCaptureManager {
    data class KeyFrames(
        val openFrame: Bitmap?,
        val closeFrame: Bitmap?,
    )

    fun extractKeyFrames(videoPath: String?): KeyFrames {
        if (videoPath.isNullOrBlank()) return KeyFrames(null, null)
        return KeyFrames(null, null)
    }
}
