@echo off
chcp 65001 >nul
title 微信消息接收服务

rem ===== 访问令牌（App 侧 sync_config.xml 的 auth_token 必须与此一致）=====
rem 真实令牌放在 secrets.local.bat（已 gitignore，不入库），格式：set WEIXIN_TOKEN=你的令牌
if exist "%~dp0secrets.local.bat" (
    call "%~dp0secrets.local.bat"
) else (
    echo [错误] 未找到 "%~dp0secrets.local.bat"
    echo        服务端现在要求令牌：缺令牌时所有数据接口都返回 503，不会再无鉴权启动。
    echo        请创建该文件并写入一行，例如：set WEIXIN_TOKEN=你的令牌
    echo        若只想本机自测，可以写：set WEIXIN_ALLOW_NO_AUTH=1
    exit /b 1
)

rem adb 可通过环境变量 ADB 指定完整路径，否则使用 PATH 中的 adb
if "%ADB%"=="" set ADB=adb

rem 模拟器地址与本地端口可用环境变量覆盖
if "%DEVICE_ADDR%"=="" set DEVICE_ADDR=127.0.0.1:16384
if "%LOCAL_PORT%"=="" set LOCAL_PORT=8000

echo [1/3] 连接模拟器 (%DEVICE_ADDR%) ...
"%ADB%" connect %DEVICE_ADDR%
if errorlevel 1 (
    echo [错误] adb connect 失败：请确认模拟器已启动，adb 可用 ADB 环境变量指定完整路径
    exit /b 1
)

echo [2/3] 配置端口转发 adb reverse tcp:%LOCAL_PORT% ...
"%ADB%" -s %DEVICE_ADDR% reverse tcp:%LOCAL_PORT% tcp:%LOCAL_PORT%
if errorlevel 1 (
    echo [错误] adb reverse 失败：模拟器未连接，或本地端口被占用
    exit /b 1
)

rem 优先用本目录 .venv 里的解释器，找不到再退回 PATH 中的 python
if exist "%~dp0.venv\Scripts\python.exe" (
    set "PYEXE=%~dp0.venv\Scripts\python.exe"
) else (
    set "PYEXE=python"
    echo [提示] 未找到 .venv\Scripts\python.exe，改用 PATH 中的 python
)

echo [3/3] 启动 Flask 服务端，按 Ctrl+C 停止
echo 服务地址: http://127.0.0.1:%LOCAL_PORT%/  (网页查询同址)
echo.
"%PYEXE%" "%~dp0app.py"
