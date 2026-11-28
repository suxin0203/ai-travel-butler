@echo off
chcp 65001 >nul
title AI 旅行管家 - 启动器
cd /d "%~dp0"

rem Maven 优先用 PATH 里的，没有就用本机 wrapper 发行版
set "MVN=mvn"
where mvn >nul 2>nul || set "MVN=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.16\0daed3be3ebd1c706f0e69e8b07c6b73f5cc4ea3dfce72a8d0ec2e849ca2ddb0\bin\mvn.cmd"

set "BACKEND_RUNNING="
set "FRONTEND_RUNNING="
netstat -ano | findstr ":8080" | findstr "LISTENING" >nul && set "BACKEND_RUNNING=1"
netstat -ano | findstr ":5173" | findstr "LISTENING" >nul && set "FRONTEND_RUNNING=1"

if defined BACKEND_RUNNING (
    echo [跳过] 后端 8080 已在运行（可能是 IDEA 实例）
) else (
    echo [启动] 后端 Spring Boot 8080（新窗口，约 10~20 秒就绪）...
    start "AI旅行管家-后端8080" cmd /k ""%MVN%" -s travel-java/.mvn/settings.xml -f travel-java/pom.xml spring-boot:run"
)

if defined FRONTEND_RUNNING (
    echo [跳过] 前端 5173 已在运行
) else (
    echo [启动] 前端 Vite 5173（新窗口，数秒就绪）...
    start "AI旅行管家-前端5173" cmd /k "cd /d travel-web && npm run dev"
)

if defined BACKEND_RUNNING if defined FRONTEND_RUNNING (
    echo 两个服务都已在运行，无需启动。
) else (
    echo 等待服务就绪...
    timeout /t 10 /nobreak >nul
)

echo.
echo ========== 启动完成，访问地址 ==========
echo   本机     : http://localhost:5173
echo   手机同WiFi: http://192.168.101.121:5173
echo   公网隧道 : https://cy1886nx5227.vicp.fun
echo   后端 API : http://localhost:8080
echo ======================================
echo   停止请双击「一键停止.bat」
pause
