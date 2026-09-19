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
        // android-serialport 2.1.2 **没有 3 参构造器** —— javap 实证只有
        // (File,int) / (File,int,int,int,int) / (File,int,int,int,int,int)。
        // 2 参形式在字节码里委托为 dataBits=8, parity=0, stopBits=1, flags=0，
        // 正是本类需要的 19200 8N1，与原先 `(device, baudRate, 0)` 的意图等价。
        serialPort = SerialPort(device, baudRate)
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
