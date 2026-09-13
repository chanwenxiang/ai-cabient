@echo off
REM PERF-1: JMeter order-read scale (token via file — JWT 勿走 -JTOKEN，cmd 会截断)
REM Usage: scripts\perf\run-order-read-scale.cmd [USERS] [RAMP] [DURATION]
setlocal
set JMETER_HOME=C:\Users\cwx\OneDrive\Desktop\apache-jmeter-5.6.3
set HEAP=-Xms1g -Xmx4g
set USERS=%1
if "%USERS%"=="" set USERS=1000
set RAMP=%2
if "%RAMP%"=="" set RAMP=60
set DURATION=%3
if "%DURATION%"=="" set DURATION=120
set ROOT=%~dp0..\..
set OUT=%ROOT%\docs\uat-screenshots\2026-09-12\jmeter-order-read
if not exist "%OUT%" mkdir "%OUT%"
cd /d "%ROOT%"

echo Fetching token...
node --input-type=module -e "import fs from 'fs'; const r=await fetch('http://127.0.0.1/api/v2/auth/password-login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({phoneNumber:'13800138000',password:'123456'})}); const j=await r.json(); if(j.code!==0){console.error(j);process.exit(1)}; fs.writeFileSync('docs/uat-screenshots/2026-09-12/jmeter-order-read/token.txt', j.data.token);"
if errorlevel 1 exit /b 1

set TOKEN_FILE=docs/uat-screenshots/2026-09-12/jmeter-order-read/token.txt
if exist "%OUT%\results.jtl" del /f "%OUT%\results.jtl"
if exist "%OUT%\report" rmdir /s /q "%OUT%\report"

echo Running JMeter USERS=%USERS% RAMP=%RAMP% DURATION=%DURATION%
"%JMETER_HOME%\bin\jmeter.bat" -n -t "%~dp0order_read_scale.jmx" -l "%OUT%\results.jtl" -e -o "%OUT%\report" -JUSERS=%USERS% -JRAMP=%RAMP% -JDURATION=%DURATION% -JBASE_HOST=127.0.0.1 -JBASE_PORT=18080 -JTOKEN_FILE=%TOKEN_FILE% -JDEVICE_ID=777740024057
set RC=%ERRORLEVEL%
echo JMeter exit=%RC%
echo Report: %OUT%\report\index.html
exit /b %RC%
