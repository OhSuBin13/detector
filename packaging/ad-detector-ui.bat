@echo off
rem ad-detector result screen launcher (keep this file ASCII-only, CRLF line endings)
rem   Double-click: the result screen opens in the default browser. Enter a URL and press the start button.
rem   The screen is reachable from this PC only (127.0.0.1). Close this window or press Ctrl+C to quit.
rem Result files (result.json ...) are written next to this script.
setlocal
set "AD_HOME=%~dp0"
if "%AD_HOME:~-1%"=="\" set "AD_HOME=%AD_HOME:~0,-1%"

set "AD_JAVA=%AD_HOME%\runtime\bin\java.exe"
if not exist "%AD_JAVA%" set "AD_JAVA=java"

set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set AD_DRIVER=
if exist "%AD_HOME%\driver\node.exe" set AD_DRIVER="-Dplaywright.cli.dir=%AD_HOME%\driver"

"%AD_JAVA%" -Xmx1g "-Daddetector.home=%AD_HOME%" %AD_DRIVER% -cp "%AD_HOME%\lib\*" addetector.App --ui %*
set "AD_EXIT=%ERRORLEVEL%"
if not "%AD_EXIT%"=="0" pause
endlocal & exit /b %AD_EXIT%
