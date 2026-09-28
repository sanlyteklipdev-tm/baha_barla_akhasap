#Requires -Version 5.1
# Устанавливает службу Baha barla на компьютер, где стоит SQL Server.
# Запускается через install.cmd. Повторный запуск безопасен: служба
# переустанавливается с новыми ответами.

$ErrorActionPreference = 'Stop'
$ServiceName = 'BahaBarlaApi'
$AppDir = 'C:\ProgramData\BahaBarlaApi'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Source = Join-Path $Here 'BahaBarlaApi'

function Ask([string]$question, [string]$default) {
    $answer = Read-Host "$question [$default]"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $default }
    return $answer.Trim()
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host "ОШИБКА: $message" -ForegroundColor Red
    Read-Host 'Нажмите Enter, чтобы закрыть'
    exit 1
}

# Окно открыто от имени администратора и закрылось бы вместе с ошибкой,
# так ничего и не показав.
trap { Fail $_.Exception.Message }

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) { Fail 'Запустите install.cmd правой кнопкой -> "Запуск от имени администратора".' }

if (-not (Test-Path (Join-Path $Source 'BahaBarlaApi.exe'))) {
    Fail "Не найдена папка BahaBarlaApi рядом с установщиком. Распакуйте архив целиком."
}

Write-Host ''
Write-Host '=== Установка службы Baha barla ===' -ForegroundColor Cyan
Write-Host ''
Write-Host 'SQL Server на этом же компьютере -- оставьте localhost.'
Write-Host 'Если SQL Server установлен как именованный экземпляр, укажите, например: localhost\SQLEXPRESS'
$sqlServer = Ask 'Адрес SQL Server' 'localhost'
$port = Ask 'Порт для телефонов' '8080'
if ($port -notmatch '^\d+$' -or [int]$port -lt 1 -or [int]$port -gt 65535) { Fail "Неверный порт: $port" }

# --- Остановить старую службу, если она есть --------------------------------
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host 'Останавливаю старую службу...'
    if ($existing.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
    & sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 3
}

# --- Файлы ---------------------------------------------------------------------
Write-Host "Копирую файлы в $AppDir ..."
New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
Copy-Item -Path (Join-Path $Source '*') -Destination $AppDir -Recurse -Force
# Остаток от старых версий, где пароль sa хранился на компьютере со службой.
# Теперь служба входит под логином, введённым на телефоне, и он не нужен.
Remove-Item (Join-Path $AppDir 'appsettings.Local.json') -Force -ErrorAction SilentlyContinue

$settings = [ordered]@{
    Logging      = @{ LogLevel = [ordered]@{ Default = 'Information'; 'Microsoft.AspNetCore' = 'Warning' } }
    AllowedHosts = '*'
    Api          = @{ Port = [int]$port }
    Sql          = [ordered]@{
        Server           = $sqlServer
        DefaultDatabase  = ''
        # Пусто = телефон может указать любую базу; пустит ли SQL Server,
        # решают права логина.
        AllowedDatabases = @()
    }
}
$json = $settings | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $AppDir 'appsettings.json'), $json, (New-Object Text.UTF8Encoding $false))

# --- Служба --------------------------------------------------------------------
Write-Host 'Регистрирую службу...'
New-Service -Name $ServiceName `
    -BinaryPathName "`"$AppDir\BahaBarlaApi.exe`"" `
    -DisplayName 'Baha barla bridge' `
    -Description 'Поиск товаров для телефонов Baha barla. Подключается к SQL Server под логином сотрудника.' `
    -StartupType Automatic | Out-Null
# Если SQL Server ещё не поднялся после включения компьютера -- пробовать снова.
& sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/5000/restart/60000 | Out-Null
# Ждать запуска SQL Server при старте Windows, а не стартовать раньше него.
& sc.exe config $ServiceName start= delayed-auto | Out-Null

# --- Брандмауэр ----------------------------------------------------------------
# Только из своей локальной сети, но в любом профиле: во многих магазинах
# Windows считает сеть "общественной", и правило "только частная" телефоны
# бы не пропустило.
Write-Host "Открываю порт $port в брандмауэре..."
Get-NetFirewallRule -DisplayName 'Baha barla bridge*' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "Baha barla bridge $port" -Direction Inbound -Action Allow `
    -Protocol TCP -LocalPort ([int]$port) -RemoteAddress LocalSubnet -Profile Any | Out-Null

Write-Host 'Запускаю службу...'
Start-Service -Name $ServiceName

$ok = $false
for ($i = 0; $i -lt 20 -and -not $ok; $i++) {
    Start-Sleep -Seconds 1
    try {
        $health = Invoke-RestMethod -Uri "http://localhost:$port/health" -TimeoutSec 3
        $ok = $health.ok
    } catch { }
}
if (-not $ok) {
    Fail "Служба не отвечает на порту $port. Возможно, порт занят другой программой -- запустите установку снова и укажите другой порт."
}

# --- Проверка SQL Server -------------------------------------------------------
# Без логина служба в базу не заходит, поэтому здесь проверяется только то,
# что SQL Server вообще слушает. Именованный экземпляр слушает не 1433 --
# для него проверка пропускается.
if ($sqlServer -notmatch '\\') {
    $hostName = ($sqlServer -split ',')[0]
    $sqlPort = if ($sqlServer -match ',(\d+)$') { [int]$Matches[1] } else { 1433 }
    $tcp = Test-NetConnection -ComputerName $hostName -Port $sqlPort -WarningAction SilentlyContinue
    if (-not $tcp.TcpTestSucceeded) {
        Write-Host ''
        Write-Host "ВНИМАНИЕ: SQL Server на $hostName`:$sqlPort не отвечает по TCP." -ForegroundColor Yellow
        Write-Host 'Служба установлена. Если телефоны не смогут искать товары,' -ForegroundColor Yellow
        Write-Host 'проверьте в services.msc, что служба SQL Server запущена, и что включён протокол TCP/IP' -ForegroundColor Yellow
        Write-Host '(SQL Server Configuration Manager -> Network Configuration -> TCP/IP = Enabled).' -ForegroundColor Yellow
    }
}

$addresses = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -ExpandProperty IPAddress

Write-Host ''
Write-Host '=== Готово ===' -ForegroundColor Green
Write-Host 'На телефоне в Settings -> Server Address укажите адрес этого компьютера:'
foreach ($a in $addresses) {
    $shown = if ($port -eq '8080') { $a } else { "$a`:$port" }
    Write-Host "    $shown" -ForegroundColor Green
}
Write-Host '(если адресов несколько -- тот, что из сети Wi-Fi магазина, обычно 192.168.x.x)'
Write-Host ''
Write-Host 'Не забудьте создать логины сотрудников в SQL Server (см. INSTRUKCIYA.txt).'
Read-Host 'Нажмите Enter, чтобы закрыть'
