@echo off
echo ==========================================
echo    🔍 SPOTLIGHT SETUP FOR SPRING BOOT
echo ==========================================
echo.

echo 1. Starting Spotlight...
echo.
echo If Spotlight is not installed, run: npm install -g @spotlightjs/spotlight
echo.

start "Spotlight" cmd /k "npx @spotlightjs/spotlight"

echo.
echo 2. Waiting for Spotlight to start...
timeout /t 3 /nobreak >nul

echo.
echo 3. Starting Spring Boot application...
start "Spring Boot" cmd /k "gradle bootRun"

echo.
echo 4. Waiting for Spring Boot to start...
timeout /t 10 /nobreak >nul

echo.
echo 5. Testing Spotlight connection...
echo.
echo Testing endpoints:
echo   - Health check: http://localhost:8081/api/health
echo   - Spotlight test: http://localhost:8081/api/test-spotlight
echo   - Test error: http://localhost:8081/api/test-error
echo.

curl -s http://localhost:8081/api/health
echo.
echo.
curl -s http://localhost:8081/api/test-spotlight
echo.
echo.

echo ==========================================
echo    🔍 SPOTLIGHT INTEGRATION READY
echo ==========================================
echo.
echo Spotlight UI: http://localhost:8969
echo Spring Boot: http://localhost:8081
echo.
echo Test endpoints:
echo   - http://localhost:8081/api/test-spotlight
echo   - http://localhost:8081/api/test-error
echo   - http://localhost:8081/api/divide-by-zero
echo.
pause 