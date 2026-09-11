@echo off
REM Installs the Baha barla bridge as a Windows service. Run as administrator.
REM Publish first, from server\BahaBarlaApi:
REM     dotnet publish -c Release -o "C:\ProgramData\BahaBarlaApi"
REM Re-running this script is safe: it replaces the existing service.
setlocal
set APPDIR=C:\ProgramData\BahaBarlaApi
set EXE=%APPDIR%\BahaBarlaApi.exe

if not exist "%EXE%" (
  echo Nothing published at %APPDIR% -- run dotnet publish first.
  exit /b 1
)
if not exist "%APPDIR%\appsettings.Local.json" (
  echo WARNING: appsettings.Local.json is missing -- the SQL password is not set.
)

sc.exe stop BahaBarlaApi >nul 2>&1
sc.exe delete BahaBarlaApi >nul 2>&1

sc.exe create BahaBarlaApi binPath= "%EXE%" start= auto DisplayName= "Baha barla bridge" || exit /b 1
sc.exe description BahaBarlaApi "Serves product searches to the Baha barla phones; speaks to SQL Server 2014 on their behalf."

REM The accounting server is not always up, so let Windows retry instead of
REM leaving the service dead: 5s, 5s, then every minute; counter resets daily.
sc.exe failure BahaBarlaApi reset= 86400 actions= restart/5000/restart/5000/restart/60000

REM The phones reach the bridge over the office Wi-Fi, so private and domain
REM profiles only -- never public.
netsh advfirewall firewall delete rule name="Baha barla bridge 8080" >nul 2>&1
netsh advfirewall firewall add rule name="Baha barla bridge 8080" dir=in action=allow protocol=TCP localport=8080 profile=private,domain

sc.exe start BahaBarlaApi
sc.exe query BahaBarlaApi
endlocal
