@echo off
echo Closing ports 8080, 8081, 3000...

for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING" 2^>nul') do (
    echo Killing PID %%a on port 8080
    taskkill /F /PID %%a >nul 2>&1
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8081 " ^| findstr "LISTENING" 2^>nul') do (
    echo Killing PID %%a on port 8081
    taskkill /F /PID %%a >nul 2>&1
)

for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":3000 " ^| findstr "LISTENING" 2^>nul') do (
    echo Killing PID %%a on port 3000
    taskkill /F /PID %%a >nul 2>&1
)

echo Ports cleared. Starting all servers...

start "Backend :8080" cmd /k "cd /d %~dp0 && gradlew.bat bootRun"
start "RAG Pipeline :8081" cmd /k "cd /d %~dp0rag-pipeline && gradlew.bat bootRun"
start "Frontend :3000" cmd /k "cd /d %~dp0timiroom-frontend && npm run dev"

echo All servers started. Close this window.
