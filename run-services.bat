@echo off
title Saasify Microservices Runner
echo ======================================================================
echo                  Saasify Microservices Runner
echo ======================================================================
echo.

:: Step 0: Locate Maven (mvn)
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 goto mvn_found

echo [INFO] mvn not found in system PATH. Searching for IntelliJ bundled Maven...

:: Search for mvn.cmd inside C:\Program Files\JetBrains
set "MVN_DIR="
for /d %%d in ("C:\Program Files\JetBrains\IntelliJ IDEA*") do (
    if exist "%%d\plugins\maven\lib\maven3\bin\mvn.cmd" (
        set "MVN_DIR=%%d\plugins\maven\lib\maven3\bin"
    )
)

if not defined MVN_DIR goto mvn_not_found
echo [INFO] Found bundled Maven at: "%MVN_DIR%"
set "PATH=%MVN_DIR%;%PATH%"
goto mvn_found

:mvn_not_found
echo [ERROR] Maven (mvn) was not found in your system PATH or IntelliJ installation.
echo Please make sure Maven is installed and added to your PATH environment variable.
pause
exit /b 1

:mvn_found
echo [INFO] Maven is ready.
echo.

:: Step 1: Start Docker Infrastructure
echo [1/4] Starting Docker infrastructure (MySQL, Redis, Kafka, Zipkin, etc.)...
docker compose up -d
if errorlevel 1 (
    echo WARNING: Failed to start docker-compose. Make sure Docker Desktop is running.
    echo Press any key to continue anyway, or Ctrl+C to abort.
    pause > nul
)
echo.

:: Step 2: Build the project
set /p build="Do you want to build the Maven projects first? (y/n) [default: y]: "
if "%build%"=="" set build=y
if /i "%build%"=="n" goto skip_build

echo [2/4] Building Maven modules (skipping tests)...
call mvn clean install -Dmaven.test.skip=true
if errorlevel 1 (
    echo ERROR: Maven build failed. Please fix compile errors before running.
    pause
    exit /b 1
)
goto build_done

:skip_build
echo [2/4] Skipping Maven build.

:build_done
echo.

:: Step 3: Start Eureka Registry Server
echo [3/4] Starting Eureka Server in a new window...
start "Eureka Server" cmd /k "mvn spring-boot:run -pl eureka-server"
echo Waiting 15 seconds for Eureka Server to initialize...
timeout /t 15 /nobreak
echo.

:: Step 4: Start remaining microservices
echo [4/4] Starting microservices in separate windows...

echo Starting API Gateway...
start "API Gateway" cmd /k "mvn spring-boot:run -pl api-gateway"
timeout /t 2 /nobreak

echo Starting Tenant Service...
start "Tenant Service" cmd /k "mvn spring-boot:run -pl tenant-service"
timeout /t 2 /nobreak

echo Starting Auth Service...
start "Auth Service" cmd /k "mvn spring-boot:run -pl auth-service"
timeout /t 2 /nobreak

echo Starting User Service...
start "User Service" cmd /k "mvn spring-boot:run -pl user-service"
timeout /t 2 /nobreak

echo Starting Billing Service...
start "Billing Service" cmd /k "mvn spring-boot:run -pl billing-service"

echo.
echo ======================================================================
echo All microservices have been launched in separate terminal windows.
echo You can monitor their logs individually in their respective windows.
echo ======================================================================
pause
