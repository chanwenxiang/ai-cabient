package com.aicabinet.edge.video

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 购物会话录像：单摄（后置 TOP）或双摄（后置 TOP + 前置 SIDE）。
 * 与 device-simulator 的 MULTI 模式及 vision-service 融合对齐。
 */
class SessionVideoRecorder(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var sessionId: String? = null
    private var topRecording: Recording? = null
    private var sideRecording: Recording? = null
    private var topFile: File? = null
    private var sideFile: File? = null

    fun start(sessionId: String) {
        this.sessionId = sessionId
        val multiEnabled = EdgeRuntimeConfig.multiCameraEnabled(context)
        val dir = File(context.cacheDir, "videos").apply { mkdirs() }
        topFile = File(dir, "$sessionId-top.mp4")
        sideFile = if (multiEnabled) File(dir, "$sessionId-side.mp4") else null

        if (!hasCameraPermission()) {
            writePlaceholder(topFile!!)
            sideFile?.let { writePlaceholder(it) }
            return
        }

        try {
            bindCameras(multiEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "camera bind failed, fallback placeholder", e)
            writePlaceholder(topFile!!)
            sideFile?.let { writePlaceholder(it) }
        }
    }

    fun stop(): RecordingResult {
        val sid = sessionId.orEmpty()
        topRecording?.stop()
        sideRecording?.stop()
        topRecording = null
        sideRecording = null

        val clips = mutableListOf<VideoClipFile>()
        topFile?.takeIf { it.exists() && it.length() > 0 }?.let { clips.add(VideoClipFile("TOP", it)) }
        sideFile?.takeIf { it.exists() && it.length() > 0 }?.let { clips.add(VideoClipFile("SIDE", it)) }

        topFile = null
        sideFile = null
        sessionId = null

        if (clips.isEmpty()) {
            clips.add(VideoClipFile("TOP", writePlaceholder(File(context.cacheDir, "videos/$sid-top.mp4"))))
        } else if (clips.size == 1 && EdgeRuntimeConfig.multiCameraEnabled(context)) {
            val side = File(clips[0].file.parent, "$sid-side.mp4")
            clips[0].file.copyTo(side, overwrite = true)
            clips.add(VideoClipFile("SIDE", side))
        }

        return RecordingResult(sid, clips)
    }

    private fun bindCameras(multiEnabled: Boolean) {
        val providerFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(context)
        val latch = CountDownLatch(1)
        var error: Exception? = null

        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                provider.unbindAll()

                val topCapture = buildCapture()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    topCapture
                )
                topRecording = topCapture.output
                    .prepareRecording(context, FileOutputOptions.Builder(topFile!!).build())
                    .start(ContextCompat.getMainExecutor(context)) { Log.d(TAG, "top event=$it") }

                if (multiEnabled && sideFile != null) {
                    runCatching {
                        val sideCapture = buildCapture()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            sideCapture
                        )
                        sideRecording = sideCapture.output
                            .prepareRecording(context, FileOutputOptions.Builder(sideFile!!).build())
                            .start(ContextCompat.getMainExecutor(context)) { Log.d(TAG, "side event=$it") }
                    }.onFailure {
                        Log.w(TAG, "front camera bind failed", it)
                        sideFile = null
                    }
                }
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }, ContextCompat.getMainExecutor(context))

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw IllegalStateException("camera bind timeout")
        }
        error?.let { throw it }
    }

    private fun buildCapture(): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
        return VideoCapture.withOutput(recorder)
    }

    private fun writePlaceholder(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("AI-CABINET-VIDEO-PLACEHOLDER-${file.nameWithoutExtension}")
        return file
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "SessionVideoRecorder"
    }
}
