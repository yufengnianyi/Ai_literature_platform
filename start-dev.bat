@echo off
REM =============================================================================
REM start-dev.bat — 双击即可启动前后端（调用同目录 start-dev.ps1）
REM
REM 后端: http://localhost:8081/api
REM 前端: http://localhost:5173
REM =============================================================================
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1"
pause
