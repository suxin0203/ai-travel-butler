@echo off
chcp 65001 >nul
title AI 旅行管家 - 停止器

set "KILLED="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8080" ^| findstr "LISTENING"') do (
    echo [停止] 后端进程 PID=%%p（8080）
    taskkill /F /PID %%p >nul 2>nul
    set "KILLED=1"
)
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5173" ^| findstr "LISTENING"') do (
    echo [停止] 前端进程 PID=%%p（5173）
    taskkill /F /PID %%p >nul 2>nul
    set "KILLED=1"
)

if defined KILLED (
    echo 关闭服务窗口...
    taskkill /F /FI "WINDOWTITLE eq AI旅行管家-*" >nul 2>nul
    echo 已全部停止。
) else (
    echo 两个服务当前都没有在运行。
)
timeout /t 3 >nul
