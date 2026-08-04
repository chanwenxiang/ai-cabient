"""本地开发入口 — PyCharm 右键 Run 此文件即可启动服务。"""

import os
import sys
from pathlib import Path

# 确保工作目录为 vision-service 根目录
ROOT = Path(__file__).resolve().parent
os.chdir(ROOT)
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import uvicorn

if __name__ == "__main__":
    # reload=False 避免子进程 cwd 不一致导致找不到模型
    uvicorn.run("app.main:app", host="0.0.0.0", port=8082, reload=False)
