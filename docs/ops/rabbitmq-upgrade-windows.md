# Апгрейд RabbitMQ на Windows (standalone, один узел)

Процедура написана под конкретный случай — Risk-тикет на уязвимость в RabbitMQ 4.2.0.0,
fixed version 4.2.6, целевая версия **4.3.4** (последний релиз на 2026-08-10). Шаги общие:
для другой пары версий меняются только номера и решение по Erlang.

Скрипт: [`Upgrade-RabbitMQ.ps1`](Upgrade-RabbitMQ.ps1) — те же фазы, что и в чеклисте ниже.

> **Область применения.** Это RabbitMQ, установленный на Windows нативным installer'ом.
> К брокеру, который поднимает `docker/infra/docker-compose.yml` этого репозитория,
> процедура не относится — там версия меняется тегом образа.

---

## 0. Что скачать заранее

| Что | Откуда |
|---|---|
| `rabbitmq-server-4.3.4.exe` | https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.4 |
| Erlang/OTP installer (**только если нужен**, см. шаг 2) | https://github.com/erlang/otp/releases |
| Таблица совместимости RabbitMQ ↔ Erlang | https://www.rabbitmq.com/docs/which-erlang |
| Release notes 4.3.0 — breaking changes | https://github.com/rabbitmq/rabbitmq-server/releases/tag/v4.3.0 |

Скачивать **только** с github.com/rabbitmq — не с зеркал. Скрипт на фазе `Install` проверяет
Authenticode-подпись и печатает SHA256 для сверки с чексуммой на странице релиза.

---

## 1. Подготовка (без даунтайма)

- [ ] Согласовать окно даунтайма. Один узел = брокер будет недоступен всё время установки (реально 10–20 минут).
- [ ] Предупредить владельцев приложений-потребителей.
- [ ] Прочитать **release notes 4.3.0** на предмет breaking changes — это смена minor-ветки, а не патч.
- [ ] Скопировать `Upgrade-RabbitMQ.ps1` на целевую машину.
- [ ] Открыть PowerShell **от имени администратора**.
- [ ] Разрешить выполнение скрипта в текущей сессии:
      `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`

---

## 2. Preflight — разведка

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Preflight
```

Ничего не меняет. Из вывода нужно вытащить и **проверить глазами**:

- [ ] **Версия Erlang/OTP.** Сверить с таблицей `which-erlang` для ветки 4.3.x.
      Если текущая OTP ниже минимума — **сначала обновляем Erlang**, потом RabbitMQ.
      Порядок обратный ломает установку.
- [ ] **Каталог данных** найден (обычно
      `C:\Windows\System32\config\systemprofile\AppData\Roaming\RabbitMQ`, если служба под LocalSystem,
      либо путь из `RABBITMQ_BASE`).
- [ ] **`cluster_status`** подтверждает, что узел действительно один. Если узлов больше —
      останавливайся, для кластера нужен rolling upgrade, а не эта процедура.
- [ ] **Список feature flags** — записать, какие в состоянии `disabled`.
- [ ] **Список плагинов** — записать, сверим после апгрейда.
- [ ] **Очереди с сообщениями** — есть ли непрочитанные. Незакоммиченные сообщения в
      non-durable classic-очередях перезагрузку не переживут.

> Если нужно обновление Erlang — ставь его отдельным installer'ом **после** фазы `Stop`
> и **до** фазы `Install`. Erlang installer сам обновит `ERLANG_HOME`.

---

## 3. Backup — горячий бэкап

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Backup -Stamp 2026-08-10-1430
```

- [ ] `definitions.json` создан и **непустой** (пользователи, vhosts, политики, exchanges, bindings).
- [ ] Скопирован каталог данных.
- [ ] Сохранён `.erlang.cookie`.
- [ ] В каталоге бэкапа лежит `MANIFEST.txt` с исходными версиями и путями.

Метку `-Stamp` запиши — она понадобится, если будешь откатываться.

---

## 4. Flags — включить feature flags

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Flags
```

**Это самый важный шаг всей процедуры.** RabbitMQ 4.3 читает метаданные только тех узлов,
у которых включены все stable feature flags ветки 4.2. Пропустишь — новый брокер может
не подняться на существующем каталоге данных, и чинить придётся из бэкапа.

- [ ] Команда `enable_feature_flag all` завершилась с кодом 0.
- [ ] В списке после выполнения не осталось **stable**-флагов в состоянии `disabled`.
      (Оставшиеся `experimental` — это нормально.)

---

## 5. Stop — начало даунтайма

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Stop
```

- [ ] `rabbitmqctl stop_app` отработал.
- [ ] Служба Windows в состоянии `Stopped`.
- [ ] Процессов `erl` / `epmd` не осталось.

### 5a. Холодный бэкап (рекомендую)

Данные в покое — копия гарантированно консистентна:

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Backup -Stamp 2026-08-10-1430-cold
```

Фаза `Backup` на остановленном брокере не сможет экспортировать `definitions.json`
(для этого нужен живой узел) — он уже снят на шаге 3. Каталог данных скопируется нормально.

### 5b. Обновление Erlang — только если шаг 2 показал, что нужно

- [ ] Запустить Erlang installer.
- [ ] Убедиться, что `ERLANG_HOME` указывает на новую версию:
      `[Environment]::GetEnvironmentVariable('ERLANG_HOME','Machine')`

---

## 6. Install — переустановка

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Install -InstallerPath C:\dist\rabbitmq-server-4.3.4.exe
```

- [ ] Authenticode-подпись installer'а — `Valid`.
- [ ] SHA256 совпал с чексуммой на странице релиза.
- [ ] Старая версия удалена, новая установлена, код возврата 0.
- [ ] Скрипт печатает `Установлена версия: 4.3.4`.

Каталог данных установщик не трогает — очереди, пользователи и политики на месте.

---

## 7. Verify — запуск и проверка

```powershell
.\Upgrade-RabbitMQ.ps1 -Phase Verify
```

- [ ] Служба `Running`.
- [ ] `rabbitmq-diagnostics ping` отвечает.
- [ ] `rabbitmqctl version` → **4.3.4**.
- [ ] `check_port_listener 5672`, `check_virtual_hosts`, `check_local_alarms`, `check_running` — все зелёные.
- [ ] Список плагинов совпадает с `plugins_enabled.txt` из бэкапа.
      Если плагин пропал: `rabbitmq-plugins enable <имя>`.
- [ ] Список очередей совпадает с `queues.txt`.
- [ ] Список пользователей совпадает с `users.txt`.
- [ ] Management UI открывается: http://localhost:15672
- [ ] Приложения-потребители переподключились и гоняют сообщения.

> Если этот брокер обслуживает TaskScheduler, отдельно проверь, что плагин
> `rabbitmq_delayed_message_exchange` на месте — на нём держатся отложенный запуск
> и retry-backoff (см. `docs/INTEGRATION.md`). Плагин внешний, и его `.ez` собирается
> под конкретную ветку брокера: после смены minor-версии его, скорее всего, придётся
> переустановить новой сборкой, а не просто включить.

---

## 8. Закрытие тикета

- [ ] Приложить вывод `rabbitmqctl version` (или `status`) как доказательство версии 4.3.4.
- [ ] Указать, что 4.3.4 > 4.2.6 (fixed version из тикета).
- [ ] Отметить дату и окно даунтайма.
- [ ] Через неделю стабильной работы — удалить каталог бэкапа.

---

## План отката

Если после шага 7 брокер не поднимается или ведёт себя неправильно:

1. Остановить службу:
   `Stop-Service RabbitMQ -Force`
2. Удалить 4.3.4:
   `& 'C:\Program Files\RabbitMQ Server\uninstall.exe' /S`
3. Если обновлял Erlang — поставить обратно ту версию OTP, что была.
4. Поставить обратно 4.2.0.0 (**дистрибутив старой версии сохрани заранее!** — самый частый
   провал отката в том, что старого installer'а под рукой нет).
5. Восстановить каталог данных из `<BackupRoot>\<Stamp>-cold\data` поверх текущего.
6. Запустить службу, проверить `rabbitmqctl status`.
7. Если данные повреждены — поднять чистый узел и залить `definitions.json`:
   `rabbitmqctl import_definitions <путь>\definitions.json`
   (восстановит топологию и пользователей, но **не сами сообщения**).

> **Скачай `rabbitmq-server-4.2.0.exe` заранее** и положи рядом с бэкапом.
> Без него откат превращается в поиск дистрибутива под давлением.

---

## Известные грабли Windows

| Проблема | Причина / решение |
|---|---|
| После апгрейда брокер видит пустой узел | Служба сменила учётную запись → другой `%APPDATA%`. Сверь `StartName` службы с тем, что было в `MANIFEST.txt`. |
| `rabbitmqctl` не может подключиться | Разъехался `.erlang.cookie` между профилем службы и твоим профилем. Скопируй cookie из профиля службы в `%USERPROFILE%\.erlang.cookie`. |
| Служба не стартует после смены Erlang | Перерегистрируй: `rabbitmq-service.bat remove` затем `rabbitmq-service.bat install`. |
| Плагины отключились | Файл `enabled_plugins` не подхватился. Включи заново через `rabbitmq-plugins enable`. |
| Установщик молча ничего не сделал | Запущен не от администратора. |
