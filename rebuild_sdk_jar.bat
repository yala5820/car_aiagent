@echo off
REM ====================================================
REM Rebuild AIAgentSdk.jar from AIDL + Java sources
REM Run this from AIAgent project root after changing AIDL
REM ====================================================
echo Building AIAgent project...
call ./gradlew app:assembleDebug
if %ERRORLEVEL% neq 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo Extracting SDK class files...
set SDK_DIR=app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes
set OUTPUT_JAR=app\libs\AIAgentSdk.jar

REM Create temp dir
if exist %TEMP%\sdk_jar rmdir /s /q %TEMP%\sdk_jar
mkdir %TEMP%\sdk_jar

REM Copy SDK classes (exclude app-specific classes)
for /f %%f in ('dir /b %SDK_DIR%\com\hirain\aiagent\*.class ^| findstr /v "MainActivity CustomAdapter InputStreamCompat JavaScriptInterface"') do (
    copy "%SDK_DIR%\com\hirain\aiagent\%%f" "%TEMP%\sdk_jar\com\hirain\aiagent\%%f" >nul
)

REM Create JAR
cd %TEMP%\sdk_jar
jar cf %OUTPUT_JAR% com\
cd %~dp0

echo New JAR created at: %OUTPUT_JAR%
echo.
echo Copy to Launcher:
echo   copy %OUTPUT_JAR% ..\Launcher\app\libs\AIAgentSdk.jar
echo.
