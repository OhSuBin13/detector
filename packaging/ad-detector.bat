@echo off
rem ad-detector command line launcher (keep this file ASCII-only, CRLF line endings)
rem   ad-detector.bat URL [--out DIR] [--budget-min N] ...   (all options: ad-detector.bat --help)
rem   Run without a URL (double-click) to be asked for one.
rem Result files (result.json ...) are written next to this script unless --out is given.
setlocal
set "AD_HOME=%~dp0"
if "%AD_HOME:~-1%"=="\" set "AD_HOME=%AD_HOME:~0,-1%"

rem Use the bundled Java runtime when present, otherwise an installed java.
set "AD_JAVA=%AD_HOME%\runtime\bin\java.exe"
if not exist "%AD_JAVA%" set "AD_JAVA=java"

rem Never download browsers during a scan (offline PC). Use the bundled Playwright driver when present.
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set AD_DRIVER=
if exist "%AD_HOME%\driver\node.exe" set AD_DRIVER="-Dplaywright.cli.dir=%AD_HOME%\driver"

"%AD_JAVA%" -Xmx1g "-Daddetector.home=%AD_HOME%" %AD_DRIVER% -cp "%AD_HOME%\lib\*" addetector.App %*
set "AD_EXIT=%ERRORLEVEL%"

rem Keep the window open when started by double-click.
if "%~1"=="" pause
endlocal & exit /b %AD_EXIT%
