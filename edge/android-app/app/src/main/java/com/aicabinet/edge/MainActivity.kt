package com.aicabinet.edge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.databinding.ActivityMainBinding
import com.aicabinet.edge.service.CabinetForegroundService
import com.aicabinet.edge.status.DeviceStatusHub
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestCameraIfNeeded()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etBroker.setText(EdgeRuntimeConfig.mqttBroker(this))
        binding.switchMultiCamera.isChecked = EdgeRuntimeConfig.multiCameraEnabled(this)
        renderConfig()

        binding.switchMultiCamera.setOnCheckedChangeListener { _, checked ->
            EdgeRuntimeConfig.saveMultiCamera(this, checked)
            Toast.makeText(this, "双摄已${if (checked) "开启" else "关闭"}，下次开门生效", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveBroker.setOnClickListener {
            EdgeRuntimeConfig.saveBroker(this, binding.etBroker.text?.toString().orEmpty())
            Toast.makeText(this, "已保存，请完全退出 App 后重新打开以重连 MQTT", Toast.LENGTH_LONG).show()
        }

        binding.btnSimulateClose.setOnClickListener {
            runCatching {
                CabinetForegroundService.getController(this).simulateDoorCloseForMock()
                Toast.makeText(this, "已触发模拟关门", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "服务未就绪", Toast.LENGTH_SHORT).show()
            }
        }

        CabinetForegroundService.start(this)

        lifecycleScope.launch {
            DeviceStatusHub.status.collectLatest { status ->
                binding.tvMqtt.text = "MQTT: ${if (status.mqttConnected) "已连接" else "未连接"}"
                binding.tvDoor.text = "门状态: ${status.doorState}"
                binding.tvSession.text = status.activeSessionId?.let { "会话: $it" } ?: "会话: -"
                binding.tvEvent.text = buildString {
                    append("事件: ${status.lastEvent}")
                    status.lastError?.let { append("\n错误: $it") }
                }
            }
        }

        lifecycleScope.launch {
            runCatching {
                if (CabinetForegroundService.getController(this@MainActivity).mockDriverEnabled()) {
                    binding.btnSimulateClose.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun renderConfig() {
        binding.tvConfig.text = buildString {
            appendLine("设备 ID: ${EdgeRuntimeConfig.deviceId(this@MainActivity)}")
            appendLine("Trade: ${EdgeRuntimeConfig.tradeServiceUrl(this@MainActivity)}")
            appendLine("录像上传: 预签名 (trade-service)")
            appendLine("驱动: ${if (EdgeRuntimeConfig.useMockDriver(this@MainActivity)) "Mock" else "串口"}")
            append("双摄: ${if (EdgeRuntimeConfig.multiCameraEnabled(this@MainActivity)) "MULTI" else "SINGLE"}")
        }
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }
}
