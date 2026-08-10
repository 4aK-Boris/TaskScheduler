<#
.SYNOPSIS
    Пофазный апгрейд RabbitMQ Server на Windows (standalone, один узел).
    Сценарий: 4.2.0.0 -> 4.3.4

.DESCRIPTION
    Скрипт разбит на фазы. Запускай их ПО ОЧЕРЕДИ и читай вывод каждой,
    прежде чем идти дальше. Ничего не делает молча и не удаляет данные.

    Фазы:
      Preflight  - собрать факты: версии, сервис, пути, плагины, feature flags, очереди.
                   Ничего не меняет. Безопасно гонять сколько угодно раз.
      Backup     - экспорт definitions + копия каталогов данных и конфигов.
      Flags      - включить все stable feature flags (ОБЯЗАТЕЛЬНО перед 4.2 -> 4.3).
      Stop       - корректно остановить приложение и службу.
      Install    - удалить старый RabbitMQ и поставить новый installer.
                   Каталог данных не трогается.
      Verify     - поднять службу и прогнать health checks.

.PARAMETER Phase
    Preflight | Backup | Flags | Stop | Install | Verify

.PARAMETER InstallerPath
    Путь к скачанному rabbitmq-server-4.3.4.exe. Нужен только для фазы Install.

.PARAMETER BackupRoot
    Куда складывать бэкап. По умолчанию C:\rabbitmq-upgrade-backup.

.PARAMETER Stamp
    Метка каталога бэкапа (например 2026-08-10-1430). Задаётся вручную,
    чтобы фаза Install могла сослаться на тот же каталог, что создала фаза Backup.

.EXAMPLE
    # 1. Разведка
    .\Upgrade-RabbitMQ.ps1 -Phase Preflight

    # 2. Бэкап (запомни выведенный Stamp!)
    .\Upgrade-RabbitMQ.ps1 -Phase Backup -Stamp 2026-08-10-1430

    # 3. Feature flags
    .\Upgrade-RabbitMQ.ps1 -Phase Flags

    # 4. Остановка
    .\Upgrade-RabbitMQ.ps1 -Phase Stop

    # 5. Установка
    .\Upgrade-RabbitMQ.ps1 -Phase Install -InstallerPath C:\dist\rabbitmq-server-4.3.4.exe

    # 6. Проверка
    .\Upgrade-RabbitMQ.ps1 -Phase Verify

.NOTES
    Запускать в PowerShell ОТ ИМЕНИ АДМИНИСТРАТОРА.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Backup', 'Flags', 'Stop', 'Install', 'Verify')]
    [string]$Phase,

    [string]$InstallerPath,

    [string]$BackupRoot = 'C:\rabbitmq-upgrade-backup',

    [string]$Stamp = 'manual'
)

$ErrorActionPreference = 'Stop'

# ----------------------------------------------------------------------------
# Хелперы вывода
# ----------------------------------------------------------------------------

function Write-Head($text) {
    Write-Host ''
    Write-Host ('=' * 78) -ForegroundColor Cyan
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ('=' * 78) -ForegroundColor Cyan
}

function Write-Step($text) { Write-Host "[ .. ] $text" -ForegroundColor Gray }
function Write-Ok($text)   { Write-Host "[ OK ] $text" -ForegroundColor Green }
function Write-Warn2($text){ Write-Host "[WARN] $text" -ForegroundColor Yellow }
function Write-Fail($text) { Write-Host "[FAIL] $text" -ForegroundColor Red }

# ----------------------------------------------------------------------------
# Проверка прав администратора
# ----------------------------------------------------------------------------

function Assert-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Нужны права администратора. Открой PowerShell через "Запуск от имени администратора".'
    }
}

# ----------------------------------------------------------------------------
# Обнаружение установки
# ----------------------------------------------------------------------------

$script:RabbitRoot = 'C:\Program Files\RabbitMQ Server'

function Get-RabbitPaths {
    if (-not (Test-Path $script:RabbitRoot)) {
        throw "Каталог '$script:RabbitRoot' не найден. Проверь путь установки."
    }

    # Внутри лежит rabbitmq_server-<version>\sbin
    $serverDir = Get-ChildItem -Path $script:RabbitRoot -Directory `
                    -Filter 'rabbitmq_server-*' -ErrorAction SilentlyContinue |
                 Sort-Object Name -Descending | Select-Object -First 1

    if ($null -eq $serverDir) {
        throw "Внутри '$script:RabbitRoot' нет каталога rabbitmq_server-*. Установка повреждена?"
    }

    $sbin = Join-Path $serverDir.FullName 'sbin'

    [pscustomobject]@{
        Root         = $script:RabbitRoot
        ServerDir    = $serverDir.FullName
        Version      = $serverDir.Name -replace '^rabbitmq_server-', ''
        Sbin         = $sbin
        Ctl          = Join-Path $sbin 'rabbitmqctl.bat'
        Diagnostics  = Join-Path $sbin 'rabbitmq-diagnostics.bat'
        Plugins      = Join-Path $sbin 'rabbitmq-plugins.bat'
        Uninstaller  = Join-Path $script:RabbitRoot 'uninstall.exe'
    }
}

function Get-RabbitDataDir {
    # RABBITMQ_BASE переопределяет расположение данных, если задан.
    $base = [Environment]::GetEnvironmentVariable('RABBITMQ_BASE', 'Machine')
    if (-not [string]::IsNullOrWhiteSpace($base)) { return $base }

    # Служба обычно крутится под LocalSystem -> её %APPDATA% вот здесь:
    $systemProfile = Join-Path $env:SystemRoot 'System32\config\systemprofile\AppData\Roaming\RabbitMQ'
    if (Test-Path $systemProfile) { return $systemProfile }

    # Запасной вариант: профиль текущего пользователя.
    $userProfile = Join-Path $env:APPDATA 'RabbitMQ'
    if (Test-Path $userProfile) { return $userProfile }

    return $null
}

function Invoke-Ctl {
    <#
      Обёртка над rabbitmqctl.bat. Возвращает объект с ExitCode и Output.
      Не бросает исключение — вызывающий сам решает, что делать с ошибкой.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$CtlPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = & $CtlPath @Arguments 2>&1 | Out-String
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output   = $output.Trim()
    }
}

# ----------------------------------------------------------------------------
# ФАЗА: Preflight
# ----------------------------------------------------------------------------

function Invoke-Preflight {
    Write-Head 'PREFLIGHT — сбор фактов, изменений не вносится'

    $p = Get-RabbitPaths
    Write-Ok "Установка найдена: $($p.ServerDir)"
    Write-Host "       Версия по каталогу: $($p.Version)"

    # --- Служба ---
    Write-Step 'Служба Windows'
    $svc = Get-Service -Name 'RabbitMQ' -ErrorAction SilentlyContinue
    if ($null -eq $svc) {
        Write-Warn2 'Служба с именем "RabbitMQ" не найдена. Ищу похожие...'
        Get-Service | Where-Object { $_.Name -like '*abbit*' } |
            Format-Table Name, DisplayName, Status -AutoSize | Out-Host
    } else {
        Write-Host "       Name=$($svc.Name)  Status=$($svc.Status)  StartType=$($svc.StartType)"
        $wmi = Get-CimInstance Win32_Service -Filter "Name='$($svc.Name)'"
        Write-Host "       Запускается под: $($wmi.StartName)"
        Write-Host "       Командная строка: $($wmi.PathName)"
    }

    # --- Erlang ---
    Write-Step 'Erlang / OTP'
    $erlangHome = [Environment]::GetEnvironmentVariable('ERLANG_HOME', 'Machine')
    if ([string]::IsNullOrWhiteSpace($erlangHome)) {
        Write-Warn2 'ERLANG_HOME не задан в переменных среды машины.'
        $erlDir = Get-ChildItem 'C:\Program Files\Erlang OTP*', 'C:\Program Files\erl*' `
                    -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($erlDir) {
            $erlangHome = $erlDir.FullName
            Write-Host "       Нашёл каталог Erlang: $erlangHome"
        }
    } else {
        Write-Host "       ERLANG_HOME = $erlangHome"
    }

    if ($erlangHome -and (Test-Path $erlangHome)) {
        $otpVersionFile = Get-ChildItem -Path (Join-Path $erlangHome 'releases') `
                            -Filter 'OTP_VERSION' -Recurse -ErrorAction SilentlyContinue |
                          Select-Object -First 1
        if ($otpVersionFile) {
            $otpVersion = (Get-Content $otpVersionFile.FullName -Raw).Trim()
            Write-Ok "Версия Erlang/OTP: $otpVersion"
            Write-Warn2 'Сверь её с таблицей: https://www.rabbitmq.com/docs/which-erlang'
            Write-Warn2 'Если для RabbitMQ 4.3.x минимум выше — СНАЧАЛА обнови Erlang, потом RabbitMQ.'
        } else {
            Write-Warn2 'Файл OTP_VERSION не найден — определи версию Erlang вручную.'
        }
    }

    # --- Каталог данных ---
    Write-Step 'Каталог данных и конфигов'
    $dataDir = Get-RabbitDataDir
    if ($dataDir) {
        Write-Ok "Каталог данных: $dataDir"
        Get-ChildItem $dataDir -ErrorAction SilentlyContinue |
            Select-Object Name, LastWriteTime | Format-Table -AutoSize | Out-Host
    } else {
        Write-Fail 'Каталог данных не найден. НЕ продолжай — без него бэкап невозможен.'
    }

    # --- Состояние брокера ---
    Write-Step 'Состояние брокера (rabbitmqctl)'
    $status = Invoke-Ctl -CtlPath $p.Ctl -Arguments @('status')
    if ($status.ExitCode -ne 0) {
        Write-Warn2 'rabbitmqctl status вернул ошибку — брокер, вероятно, не запущен:'
        Write-Host $status.Output
        Write-Warn2 'Дальнейшие проверки (feature flags, очереди) требуют работающего брокера.'
        return
    }
    Write-Ok 'Брокер отвечает.'
    Write-Host $status.Output

    Write-Step 'Топология кластера'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('cluster_status')).Output | Write-Host

    Write-Step 'Feature flags (ищи всё, что НЕ enabled)'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_feature_flags', 'name', 'state', 'stability')).Output | Write-Host

    Write-Step 'Включённые плагины'
    (& $p.Plugins list -e 2>&1 | Out-String).Trim() | Write-Host

    Write-Step 'Очереди с сообщениями (их надо разгрести до даунтайма)'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_queues', 'name', 'type', 'messages', 'consumers')).Output | Write-Host

    Write-Head 'PREFLIGHT ЗАВЕРШЁН'
    Write-Host 'Проверь глазами:'
    Write-Host '  1. Версия OTP подходит для RabbitMQ 4.3.x?'
    Write-Host '  2. Есть ли feature flags в состоянии disabled? (их включит фаза Flags)'
    Write-Host '  3. Есть ли очереди с непрочитанными сообщениями?'
    Write-Host '  4. Записан ли список плагинов — после апгрейда сверим.'
    Write-Host ''
    Write-Host 'Дальше: .\Upgrade-RabbitMQ.ps1 -Phase Backup -Stamp <метка>' -ForegroundColor Cyan
}

# ----------------------------------------------------------------------------
# ФАЗА: Backup
# ----------------------------------------------------------------------------

function Invoke-Backup {
    Write-Head 'BACKUP — экспорт definitions и копия данных'

    $p = Get-RabbitPaths
    $dest = Join-Path $BackupRoot $Stamp

    if (Test-Path $dest) {
        throw "Каталог бэкапа '$dest' уже существует. Задай другой -Stamp, чтобы не затереть прошлый бэкап."
    }
    New-Item -ItemType Directory -Path $dest -Force | Out-Null
    Write-Ok "Каталог бэкапа: $dest"

    # --- definitions (только при работающем брокере) ---
    Write-Step 'Экспорт definitions (пользователи, vhosts, политики, обменники, очереди)'
    $defFile = Join-Path $dest 'definitions.json'
    $export = Invoke-Ctl -CtlPath $p.Ctl -Arguments @('export_definitions', $defFile)
    if ($export.ExitCode -eq 0 -and (Test-Path $defFile)) {
        $size = (Get-Item $defFile).Length
        Write-Ok "definitions.json сохранён ($size байт)"
    } else {
        Write-Fail 'Экспорт definitions не удался. Брокер запущен?'
        Write-Host $export.Output
        Write-Warn2 'Без definitions откат будет болезненным. Реши это ДО остановки службы.'
    }

    # --- Текстовые снимки состояния ---
    Write-Step 'Снимки состояния в текстовые файлы'
    foreach ($item in @(
        @{ Name = 'status.txt';         Args = @('status') },
        @{ Name = 'cluster_status.txt'; Args = @('cluster_status') },
        @{ Name = 'feature_flags.txt';  Args = @('list_feature_flags', 'name', 'state', 'stability') },
        @{ Name = 'queues.txt';         Args = @('list_queues', 'name', 'type', 'messages', 'consumers') },
        @{ Name = 'users.txt';          Args = @('list_users') },
        @{ Name = 'vhosts.txt';         Args = @('list_vhosts') },
        @{ Name = 'policies.txt';       Args = @('list_policies') }
    )) {
        $r = Invoke-Ctl -CtlPath $p.Ctl -Arguments $item.Args
        $r.Output | Out-File -FilePath (Join-Path $dest $item.Name) -Encoding utf8
    }
    (& $p.Plugins list -e 2>&1 | Out-String) |
        Out-File -FilePath (Join-Path $dest 'plugins_enabled.txt') -Encoding utf8
    Write-Ok 'Снимки сохранены.'

    # --- Копия каталога данных ---
    $dataDir = Get-RabbitDataDir
    if ($null -eq $dataDir) {
        Write-Fail 'Каталог данных не найден — копировать нечего. Разберись до продолжения.'
        return
    }

    Write-Step "Копирую каталог данных: $dataDir"
    Write-Warn2 'Если брокер запущен, копия mnesia может быть неконсистентной.'
    Write-Warn2 'Надёжнее: сначала -Phase Stop, потом повторить -Phase Backup в новый Stamp.'
    $dataCopy = Join-Path $dest 'data'
    Copy-Item -Path $dataDir -Destination $dataCopy -Recurse -Force -ErrorAction Continue
    Write-Ok "Данные скопированы в: $dataCopy"

    # --- Копия конфигов и Program Files (для отката) ---
    Write-Step 'Копирую конфиги и .erlang.cookie'
    $cookiePaths = @(
        (Join-Path $env:SystemRoot 'System32\config\systemprofile\.erlang.cookie'),
        (Join-Path $env:USERPROFILE '.erlang.cookie')
    )
    foreach ($cp in $cookiePaths) {
        if (Test-Path $cp) {
            $safeName = ($cp -replace '[:\\]', '_') + '.bak'
            Copy-Item $cp (Join-Path $dest $safeName) -Force
            Write-Ok "Cookie сохранён: $cp"
        }
    }

    # --- Манифест ---
    $manifest = @"
RabbitMQ upgrade backup
=======================
Метка бэкапа      : $Stamp
Каталог установки : $($p.Root)
Версия сервера    : $($p.Version)
Каталог данных    : $dataDir
ERLANG_HOME       : $([Environment]::GetEnvironmentVariable('ERLANG_HOME','Machine'))
RABBITMQ_BASE     : $([Environment]::GetEnvironmentVariable('RABBITMQ_BASE','Machine'))
Имя машины        : $env:COMPUTERNAME
Кто снял бэкап    : $env:USERNAME
"@
    $manifest | Out-File -FilePath (Join-Path $dest 'MANIFEST.txt') -Encoding utf8

    Write-Head 'BACKUP ЗАВЕРШЁН'
    Write-Host "Всё лежит в: $dest" -ForegroundColor Cyan
    Write-Host 'Проверь, что definitions.json непустой, прежде чем идти дальше.'
    Write-Host ''
    Write-Host 'Дальше: .\Upgrade-RabbitMQ.ps1 -Phase Flags' -ForegroundColor Cyan
}

# ----------------------------------------------------------------------------
# ФАЗА: Flags
# ----------------------------------------------------------------------------

function Invoke-Flags {
    Write-Head 'FLAGS — включение всех stable feature flags'
    Write-Host 'Зачем: RabbitMQ 4.3 умеет читать метаданные только тех узлов,'
    Write-Host 'у которых включены все feature flags предыдущей ветки. Если оставить'
    Write-Host 'выключенные флаги, новый узел может не подняться на старом каталоге данных.'
    Write-Host ''

    $p = Get-RabbitPaths

    Write-Step 'Состояние ДО'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_feature_flags', 'name', 'state', 'stability')).Output | Write-Host

    Write-Step 'Включаю все stable feature flags'
    $enable = Invoke-Ctl -CtlPath $p.Ctl -Arguments @('enable_feature_flag', 'all')
    Write-Host $enable.Output
    if ($enable.ExitCode -ne 0) {
        Write-Fail 'Не удалось включить feature flags. НЕ продолжай апгрейд, пока не разберёшься.'
        return
    }
    Write-Ok 'Команда отработала.'

    Write-Step 'Состояние ПОСЛЕ'
    $after = Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_feature_flags', 'name', 'state', 'stability')
    Write-Host $after.Output

    $disabled = $after.Output -split "`n" | Where-Object { $_ -match '\bdisabled\b' }
    if ($disabled) {
        Write-Warn2 'Остались выключенные флаги:'
        $disabled | ForEach-Object { Write-Host "       $_" }
        Write-Warn2 'Experimental-флаги оставлять выключенными нормально. Stable — нет.'
    } else {
        Write-Ok 'Выключенных флагов не осталось.'
    }

    Write-Head 'FLAGS ЗАВЕРШЁН'
    Write-Host 'Дальше: .\Upgrade-RabbitMQ.ps1 -Phase Stop' -ForegroundColor Cyan
}

# ----------------------------------------------------------------------------
# ФАЗА: Stop
# ----------------------------------------------------------------------------

function Invoke-Stop {
    Write-Head 'STOP — корректная остановка брокера'
    Write-Warn2 'С этого момента начинается ДАУНТАЙМ. Продюсеры и консьюмеры отвалятся.'

    $p = Get-RabbitPaths

    Write-Step 'Очереди перед остановкой (сообщения в classic-очередях без durable будут потеряны)'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_queues', 'name', 'type', 'messages')).Output | Write-Host

    Write-Step 'rabbitmqctl stop_app — корректно гасим приложение'
    $stopApp = Invoke-Ctl -CtlPath $p.Ctl -Arguments @('stop_app')
    Write-Host $stopApp.Output
    if ($stopApp.ExitCode -eq 0) { Write-Ok 'Приложение остановлено.' }
    else { Write-Warn2 'stop_app вернул ошибку — возможно, приложение уже не работало.' }

    Write-Step 'Останавливаю службу Windows'
    $svc = Get-Service -Name 'RabbitMQ' -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.Status -ne 'Stopped') {
            Stop-Service -Name $svc.Name -Force
            $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(90))
        }
        Write-Ok "Служба $($svc.Name): $((Get-Service -Name $svc.Name).Status)"
    } else {
        Write-Warn2 'Служба "RabbitMQ" не найдена — пропускаю.'
    }

    Write-Step 'Проверяю, не остались ли живые процессы erl / epmd'
    $procs = Get-Process -Name 'erl', 'erlsrv', 'epmd' -ErrorAction SilentlyContinue
    if ($procs) {
        $procs | Select-Object Id, ProcessName, StartTime | Format-Table -AutoSize | Out-Host
        Write-Warn2 'Процессы ещё живы. Дай им 10-20 секунд; если не уйдут — гаси вручную.'
    } else {
        Write-Ok 'Процессов Erlang не осталось.'
    }

    Write-Head 'STOP ЗАВЕРШЁН'
    Write-Host 'Рекомендую СЕЙЧАС снять холодный бэкап (данные в покое = консистентная копия):' -ForegroundColor Yellow
    Write-Host '  .\Upgrade-RabbitMQ.ps1 -Phase Backup -Stamp <метка>-cold' -ForegroundColor Yellow
    Write-Host ''
    Write-Host 'Дальше: .\Upgrade-RabbitMQ.ps1 -Phase Install -InstallerPath <путь к .exe>' -ForegroundColor Cyan
}

# ----------------------------------------------------------------------------
# ФАЗА: Install
# ----------------------------------------------------------------------------

function Invoke-Install {
    Write-Head 'INSTALL — снос старой версии и установка новой'

    if ([string]::IsNullOrWhiteSpace($InstallerPath)) {
        throw 'Нужен -InstallerPath к rabbitmq-server-4.3.4.exe'
    }
    if (-not (Test-Path $InstallerPath)) {
        throw "Installer не найден: $InstallerPath"
    }

    # --- Контроль подлинности дистрибутива ---
    Write-Step 'Цифровая подпись installer'
    $sig = Get-AuthenticodeSignature -FilePath $InstallerPath
    Write-Host "       Статус: $($sig.Status)"
    Write-Host "       Подписант: $($sig.SignerCertificate.Subject)"
    if ($sig.Status -ne 'Valid') {
        Write-Fail 'Подпись невалидна. НЕ ставь этот файл — перекачай с github.com/rabbitmq/rabbitmq-server/releases'
        return
    }
    Write-Ok 'Подпись валидна.'

    Write-Step 'SHA256 (сверь с чексуммой на странице релиза)'
    $hash = (Get-FileHash -Path $InstallerPath -Algorithm SHA256).Hash
    Write-Host "       $hash"

    # --- Служба должна быть остановлена ---
    $svc = Get-Service -Name 'RabbitMQ' -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -ne 'Stopped') {
        throw 'Служба RabbitMQ ещё работает. Сначала выполни -Phase Stop.'
    }

    # --- Каталог данных не должен пострадать ---
    $dataDir = Get-RabbitDataDir
    Write-Warn2 "Каталог данных: $dataDir"
    Write-Warn2 'Установщик его НЕ удаляет — метаданные, очереди и пользователи переживут переустановку.'

    # --- Удаление старой версии ---
    $p = Get-RabbitPaths
    if (Test-Path $p.Uninstaller) {
        Write-Step "Удаляю RabbitMQ $($p.Version) (тихий режим)"
        $proc = Start-Process -FilePath $p.Uninstaller -ArgumentList '/S' -Wait -PassThru
        Write-Host "       Код возврата: $($proc.ExitCode)"
        Start-Sleep -Seconds 5
        Write-Ok 'Старая версия удалена.'
    } else {
        Write-Warn2 "uninstall.exe не найден в $($p.Root) — ставлю поверх."
    }

    # --- Установка новой версии ---
    Write-Step 'Ставлю новую версию (тихий режим, /S)'
    $proc = Start-Process -FilePath $InstallerPath -ArgumentList '/S' -Wait -PassThru
    Write-Host "       Код возврата: $($proc.ExitCode)"
    if ($proc.ExitCode -ne 0) {
        Write-Fail "Установщик вернул $($proc.ExitCode). Смотри логи установки."
        return
    }
    Start-Sleep -Seconds 10

    # --- Что получилось ---
    $pNew = Get-RabbitPaths
    Write-Ok "Установлена версия: $($pNew.Version)"
    Write-Host "       Каталог: $($pNew.ServerDir)"

    Write-Head 'INSTALL ЗАВЕРШЁН'
    Write-Host 'Дальше: .\Upgrade-RabbitMQ.ps1 -Phase Verify' -ForegroundColor Cyan
}

# ----------------------------------------------------------------------------
# ФАЗА: Verify
# ----------------------------------------------------------------------------

function Invoke-Verify {
    Write-Head 'VERIFY — запуск и проверка'

    $p = Get-RabbitPaths
    Write-Ok "Версия по каталогу: $($p.Version)"

    Write-Step 'Запускаю службу'
    $svc = Get-Service -Name 'RabbitMQ' -ErrorAction SilentlyContinue
    if ($null -eq $svc) {
        Write-Fail 'Служба "RabbitMQ" не зарегистрирована. Переустанови или зарегистрируй вручную:'
        Write-Host "       & '$($p.Sbin)\rabbitmq-service.bat' install"
        return
    }
    if ($svc.Status -ne 'Running') {
        Start-Service -Name $svc.Name
        $svc.WaitForStatus('Running', [TimeSpan]::FromSeconds(120))
    }
    Write-Ok "Служба: $((Get-Service -Name $svc.Name).Status)"

    Write-Step 'Жду, пока брокер прогрузится'
    $ready = $false
    foreach ($attempt in 1..24) {
        $r = Invoke-Ctl -CtlPath $p.Diagnostics -Arguments @('ping')
        if ($r.ExitCode -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 5
    }
    if (-not $ready) {
        Write-Fail 'Брокер не отвечает за 2 минуты. Смотри логи:'
        $dataDir = Get-RabbitDataDir
        Write-Host "       $dataDir\log\"
        return
    }
    Write-Ok 'Брокер отвечает на ping.'

    # --- Health checks ---
    Write-Step 'Проверка версии, зафиксированной самим брокером'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('version')).Output | Write-Host

    foreach ($check in @(
        @{ Label = 'Порты слушаются';       Args = @('check_port_listener', '5672') },
        @{ Label = 'Виртуальные хосты живы'; Args = @('check_virtual_hosts') },
        @{ Label = 'Локальные алармы';       Args = @('check_local_alarms') },
        @{ Label = 'Узел здоров';            Args = @('check_running') }
    )) {
        Write-Step $check.Label
        $r = Invoke-Ctl -CtlPath $p.Diagnostics -Arguments $check.Args
        if ($r.ExitCode -eq 0) { Write-Ok $check.Label } else { Write-Fail "$($check.Label): $($r.Output)" }
    }

    Write-Step 'Плагины (сверь со списком из бэкапа: plugins_enabled.txt)'
    (& $p.Plugins list -e 2>&1 | Out-String).Trim() | Write-Host

    Write-Step 'Очереди (сверь с queues.txt из бэкапа)'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_queues', 'name', 'type', 'messages', 'consumers')).Output | Write-Host

    Write-Step 'Пользователи (сверь с users.txt из бэкапа)'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_users')).Output | Write-Host

    Write-Step 'Feature flags после апгрейда'
    (Invoke-Ctl -CtlPath $p.Ctl -Arguments @('list_feature_flags', 'name', 'state', 'stability')).Output | Write-Host

    Write-Head 'VERIFY ЗАВЕРШЁН'
    Write-Host 'Осталось руками:'
    Write-Host '  1. Проверить, что приложения подключаются и гоняют сообщения.'
    Write-Host '  2. Открыть management UI: http://localhost:15672'
    Write-Host '  3. Приложить к тикету вывод rabbitmqctl version как доказательство.'
    Write-Host '  4. Если всё стабильно неделю — можно чистить каталог бэкапа.'
}

# ----------------------------------------------------------------------------
# Точка входа
# ----------------------------------------------------------------------------

Assert-Admin

switch ($Phase) {
    'Preflight' { Invoke-Preflight }
    'Backup'    { Invoke-Backup }
    'Flags'     { Invoke-Flags }
    'Stop'      { Invoke-Stop }
    'Install'   { Invoke-Install }
    'Verify'    { Invoke-Verify }
}
