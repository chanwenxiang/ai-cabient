package com.aicabinet.edge.vision

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * 从录制视频中提取关键帧用于边缘推理。
 * 开门帧: 视频第 100ms 处（货柜初始状态）
 * 关门帧: 视频倒数第 500ms 处（用户拿走商品后）
 */
class FrameCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "FrameCapture"
        private const val OPEN_FRAME_MS = 100L
        private const val CLOSE_FRAME_OFFSET_MS = 500L
    }

    data class CapturedFrame(
        val sessionId: String,
        val camera: String,
        val bitmap: Bitmap,
        val captureTimeMs: Long,
        val isOpenFrame: Boolean
    )

    /**
     * 从视频文件中提取开门帧（视频开头附近）。
     */
    fun extractOpenFrame(sessionId: String, camera: String, videoFile: File): CapturedFrame? {
        return extractFrame(sessionId, camera, videoFile, OPEN_FRAME_MS, isOpenFrame = true)
    }

    /**
     * 从视频文件中提取关门帧（视频结尾前）。
     */
    fun extractCloseFrame(sessionId: String, camera: String, videoFile: File): CapturedFrame? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val positionMs = (durationMs - CLOSE_FRAME_OFFSET_MS).coerceAtLeast(0L)
            val frame = extractFrame(sessionId, camera, videoFile, positionMs, isOpenFrame = false)
            Log.d(TAG, "close frame from $camera at ${positionMs}ms (duration=${durationMs}ms)")
            frame
        } catch (e: Exception) {
            Log.w(TAG, "close frame extract failed camera=$camera", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractFrame(
        sessionId: String, camera: String, videoFile: File,
        positionMs: Long, isOpenFrame: Boolean
    ): CapturedFrame? {
        if (!videoFile.exists()) {
            Log.w(TAG, "video file not found: ${videoFile.absolutePath}")
            return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            CapturedFrame(sessionId, camera, bitmap, positionMs, isOpenFrame)
        } catch (e: Exception) {
            Log.w(TAG, "frame extract failed camera=$camera ms=$positionMs", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    fun release() {
        // no-op, Bitmaps GC'd automatically
    }
}
