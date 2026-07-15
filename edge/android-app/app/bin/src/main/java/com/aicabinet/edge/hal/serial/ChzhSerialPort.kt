package com.aicabinet.edge.hal.serial

import android.util.Log
import android.serialport.SerialPort
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 串口封装，基于 licheedev android-serialport（19200 8N1）。
 */
class ChzhSerialPort(
    private val devicePath: String,
    private val baudRate: Int = 19200
) : AutoCloseable {

    private var serialPort: SerialPort? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun open(): Result<Unit> = runCatching {
        val device = File(devicePath)
        if (!device.exists()) {
            throw IllegalStateException("serial device not found: $devicePath")
        }
        serialPort = SerialPort(device, baudRate, 0)
        input = serialPort!!.inputStream
        output = serialPort!!.outputStream
        Log.i(TAG, "serial opened path=$devicePath baud=$baudRate")
    }

    fun write(data: ByteArray) {
        output?.write(data)
        output?.flush()
    }

    fun read(buffer: ByteArray): Int = input?.read(buffer) ?: -1

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { serialPort?.close() }
        input = null
        output = null
        serialPort = null
    }

    companion object {
        private const val TAG = "ChzhSerialPort"
    }
}
