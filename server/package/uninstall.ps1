#Requires -Version 5.1
# Удаляет службу Baha barla, её файлы и правило брандмауэра.
$ErrorActionPreference = 'Stop'
$ServiceName = 'BahaBarlaApi'
$AppDir = 'C:\ProgramData\BahaBarlaApi'

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Write-Host 'Запустите uninstall.cmd от имени администратора.' -ForegroundColor Red
    Read-Host 'Нажмите Enter, чтобы закрыть'
    exit 1
}

$service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
    & sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 3
}
Get-NetFirewallRule -DisplayName 'Baha barla bridge*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
if (Test-Path $AppDir) { Remove-Item $AppDir -Recurse -Force }

Write-Host 'Служба Baha barla удалена. Базы данных SQL Server не затронуты.' -ForegroundColor Green
Read-Host 'Нажмите Enter, чтобы закрыть'
