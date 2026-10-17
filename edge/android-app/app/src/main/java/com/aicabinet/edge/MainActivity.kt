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
        renderConfig()

        binding.btnSaveBroker.setOnClickListener {
            EdgeRuntimeConfig.saveBroker(this, binding.etBroker.text?.toString().orEmpty())
            Toast.makeText(this, "已保存，请完全退出 App 后重新打开以重连 MQTT", Toast.LENGTH_LONG).show()
        }

        // 审计 A1/P0-2：设备 HTTP 凭据装机注入（签发响应一次性明文，此后仅 KeyStore 密文落盘）
        binding.btnSaveCredential.setOnClickListener {
            val deviceId = binding.etProvisionDeviceId.text?.toString()?.trim().orEmpty()
            val secret = binding.etProvisionSecret.text?.toString()?.trim().orEmpty()
            if (!deviceId.matches(Regex("\\d{12}"))) {
                Toast.makeText(this, "设备编号须为 12 位数字（与运营台登记一致）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (secret.isBlank()) {
                Toast.makeText(this, "请先在运营台签发凭据并粘贴 Secret", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            EdgeRuntimeConfig.saveDeviceId(this, deviceId)
            // A6：Keystore 不可用时 saveSecret 抛异常（拒绝明文回落），装机注入失败须重试
            try {
                EdgeRuntimeConfig.saveSecret(this, "http_device_secret", secret)
            } catch (e: IllegalStateException) {
                Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            binding.etProvisionSecret.setText("")
            renderConfig()
            Toast.makeText(this, "设备凭据已保存并加密，OTA/素材链路即刻生效", Toast.LENGTH_LONG).show()
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
            appendLine("设备 ID: ${EdgeRuntimeConfig.ensureDeviceId(this@MainActivity)}")
            appendLine("Trade: ${EdgeRuntimeConfig.tradeServiceUrl(this@MainActivity)}")
            // 只显示「是否已装机」，绝不回显 secret 本体
            appendLine("设备凭据: ${if (EdgeRuntimeConfig.httpDeviceSecret(this@MainActivity).isNotBlank()) "已注入" else "未注入（OTA/素材链路停用）"}")
            append("驱动: ${if (EdgeRuntimeConfig.useMockDriver(this@MainActivity)) "Mock" else "串口"}")
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
