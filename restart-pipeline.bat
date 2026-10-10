@echo off
echo Killing process on port 8081...

for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8081 " ^| findstr "LISTENING" 2^>nul') do (
    echo Killing PID %%a on port 8081
    taskkill /F /PID %%a >nul 2>&1
)

timeout /t 1 /nobreak >nul

echo Starting RAG-Pipeline (Java) on port 8081...
start "RAG-Pipeline :8081" cmd /k "cd /d %~dp0rag-pipeline && gradlew.bat bootRun"

echo Pipeline server restarted.
