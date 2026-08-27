#!/usr/bin/env python3
"""Replace window with globalThis in Sonar typescript:S7764 flagged files."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

FILES = """
clients/admin-vue/src/api/client.ts
clients/admin-vue/src/components/GlobalSearch.vue
clients/admin-vue/src/composables/useResizableDrawer.ts
clients/admin-vue/src/composables/useSessionVideo.ts
clients/admin-vue/src/layouts/AdminLayout.vue
clients/admin-vue/src/views/dashboard/BigScreenView.vue
clients/admin-vue/src/views/devices/DeviceDetailView.vue
clients/admin-vue/src/views/disputes/DisputeListView.vue
clients/admin-vue/src/views/growth/AdAssetsView.vue
clients/admin-vue/src/views/print/PrintView.vue
clients/admin-vue/src/views/replenishment/ReplenishmentView.vue
clients/admin-vue/src/views/reports/DeviceReportView.vue
clients/admin-vue/src/views/skus/SkuListView.vue
clients/admin-vue/src/views/skus/SkuVisionEnrollView.vue
clients/admin-vue/src/views/system/DictManageView.vue
clients/admin-vue/src/views/upload/UploadQueueView.vue
clients/admin-vue/src/views/vision/RecognitionDemoView.vue
clients/admin-vue/src/views/warehouse/WarehouseView.vue
clients/consumer-mp/src/pages/dispute/detail.vue
clients/consumer-mp/src/pages/index/index.vue
clients/consumer-mp/src/pages/login/login.vue
clients/consumer-mp/src/pages/order-detail/order-detail.vue
clients/consumer-mp/src/pages/result/result.vue
clients/consumer-mp/src/pages/verify/verify.vue
clients/consumer-mp/src/utils/consumer-api.ts
clients/consumer-mp/src/utils/recharge.ts
clients/merchant-mp/src/utils/scan-cabinet.ts
clients/merchant-mp/src/utils/text-prompt.ts
""".strip().splitlines()

WINDOW = re.compile(r"\bwindow\b")


def main() -> None:
    total = 0
    for rel in FILES:
        path = ROOT / rel.strip()
        text = path.read_text(encoding="utf-8")
        new_text, n = WINDOW.subn("globalThis", text)
        if n:
            path.write_text(new_text, encoding="utf-8", newline="\n")
            print(f"{rel}: {n}")
            total += n
    print(f"done: {total} replacements in {len(FILES)} files")


if __name__ == "__main__":
    main()
