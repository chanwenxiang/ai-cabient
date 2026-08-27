"""移远 OpenVending 端侧识别提供商（占位）。

移远 OpenVending 以端侧 AI 为核心（摄像头 + AI 算力模组/边缘盒），识别在端侧完成，
并将识别结果与异常事件（错拿/遮挡/防撬）上报到平台。本模块先固定对接契约：

- 端侧识别结果建议直接上报 trade-service 内部接口（复用现有结算链路）；
- 端侧异常事件通过 ``POST /internal/v1/vision/anomaly-events`` 上报，
  落入运营异常中心并触发钉钉/企微告警；
- 待移远提供 SDK/API 文档后，在此实现 provider 的鉴权、请求与响应解析。

环境变量：
- ``QUECTEL_ENDPOINT``：移远平台/边缘盒地址
- ``QUECTEL_API_KEY``：移远接入密钥
- ``QUECTEL_ENABLED``：是否启用端侧结果直通（false 时仍走云端识别）
"""

from __future__ import annotations

import logging
import os

from app.recognition.types import RecognitionOutput

log = logging.getLogger(__name__)


class QuectelRecognizer:
    """移远端侧识别适配层占位：未接入 SDK 前始终不可用，避免静默降级。"""

    def __init__(self) -> None:
        self.endpoint = os.getenv("QUECTEL_ENDPOINT", "")
        self.api_key = os.getenv("QUECTEL_API_KEY", "")
        self.available = False
        self.model_version = "quectel-pending"
        self.load_error = (
            "QUECTEL integration not configured yet: "
            "set QUECTEL_ENDPOINT/QUECTEL_API_KEY and implement SDK adapter"
        )
        log.warning(self.load_error)

    def recognize(self, session_id: str, video_uri: str | None,
                  device_id: str | None = None,
                  mode: str | None = None) -> RecognitionOutput:
        raise NotImplementedError(self.load_error)
