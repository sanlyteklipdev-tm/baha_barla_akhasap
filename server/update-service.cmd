@echo off
REM Rebuilds the bridge and puts it under the running service.
REM Run as administrator. The service holds the DLL open, so it has to stop
REM for the copy and start again afterwards.
setlocal
set APPDIR=C:\ProgramData\BahaBarlaApi
set SRC=%~dp0BahaBarlaApi

sc.exe stop BahaBarlaApi
REM Give Windows a moment to release the file handles.
timeout /t 3 /nobreak >nul

pushd "%SRC%" || exit /b 1
dotnet publish -c Release -o "%APPDIR%"
set RC=%ERRORLEVEL%
popd

sc.exe start BahaBarlaApi
if not "%RC%"=="0" echo Publish failed with %RC% -- the old build is running again.
endlocal
