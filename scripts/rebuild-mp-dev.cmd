@echo off
chcp 65001 >nul
title 重建小程序真机产物（自动跟随最新 IP）
rem ============================================================
rem  手机预览提示"请求失败/连不上"时：双击本文件即可。
rem  自动探测电脑当前局域网 IP 并重新构建消费者小程序产物，
rem  无需改路由器、无需敲命令。构建完成后再到微信开发者工具
rem  点「编译」+ 手机重新预览。
rem ============================================================
cd /d "%~dp0..\clients\consumer-mp"
set MP_API_USE_LAN=1

echo == 正在重建消费者小程序真机产物（自动探测当前局域网 IP）==
echo.
call npm run build:mp-weixin:dev
if errorlevel 1 (
  echo.
  echo [失败] 构建出错：请把本窗口截图发给开发同事
) else (
  echo.
  echo [完成] 产物已指向最新 IP（见上方 mini-program API 一行）
  echo 下一步：微信开发者工具点「编译」，手机重新点「预览」
)
echo.
pause
