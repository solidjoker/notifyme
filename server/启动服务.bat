@echo off
chcp 65001 >nul
title 微信消息接收服务

rem ===== 访问令牌（App 侧 sync_config.xml 的 auth_token 必须与此一致）=====
rem 真实令牌放在 secrets.local.bat（已 gitignore，不入库），格式：set WEIXIN_TOKEN=你的令牌
if exist "%~dp0secrets.local.bat" (
    call "%~dp0secrets.local.bat"
) else (
    echo [警告] 未找到 secrets.local.bat，服务将以无鉴权模式运行
    set WEIXIN_TOKEN=
)

rem adb 可通过环境变量 ADB 指定完整路径，否则使用 PATH 中的 adb
if "%ADB%"=="" set ADB=adb

rem 模拟器地址与本地端口可用环境变量覆盖
if "%DEVICE_ADDR%"=="" set DEVICE_ADDR=127.0.0.1:16384
if "%LOCAL_PORT%"=="" set LOCAL_PORT=8000

echo [1/3] 连接模拟器 (%DEVICE_ADDR%) ...
"%ADB%" connect %DEVICE_ADDR%

echo [2/3] 配置端口转发 adb reverse tcp:%LOCAL_PORT% ...
"%ADB%" -s %DEVICE_ADDR% reverse tcp:%LOCAL_PORT% tcp:%LOCAL_PORT%

echo [3/3] 启动 Flask 服务端，按 Ctrl+C 停止
echo 服务地址: http://127.0.0.1:%LOCAL_PORT%/  (网页查询同址)
echo.
python "%~dp0app.py"
