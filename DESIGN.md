/# TaskScheduler — Design Document

Внутренний дизайн-документ. Цель: JobRunr-альтернатива на Kotlin/JVM с web-dashboard и Pro-фичами (chains, dependencies/barriers). Обновляется по мере принятия решений.

---

## 1. Цели и положение проекта

- **Что строим:** background job scheduler для Kotlin/JVM. Аналог JobRunr, но Kotlin-нативный и с использованием брокера для дисптача.
- **Ключевые фичи MVP:** enqueue jobs, scheduled (future-dated), recurring (cron), retry с exp backoff, chains (последовательное выполнение), barriers/dependencies ("выполни C только когда A и B успешно завершатся"), web-dashboard.
- **Что НЕ JobRunr:**
  - RabbitMQ для push-дисптача (JobRunr только polling DB)
  - Dashboard на Kotlin/JS + React — общие DTO с бэкендом вместо ручной синхронизации типов
  - Sealed-class API вместо lambda-magic (lambda capture — shipped через K2 compiler plugin, см. 21.9)
  - DAG-зависимости (chains + barriers) встроены в core, не Pro-only

---

## 2. Стек

| Слой | Технология | Обоснование |
|---|---|---|
| Язык | Kotlin/JVM | — |
| Async | kotlinx-coroutines | стандарт для Kotlin |
| Storage (source of truth) | Postgres | ACID, advisory locks, LISTEN/NOTIFY, `FOR UPDATE SKIP LOCKED` |
| Connection pool | HikariCP | стандарт |
| Миграции | Flyway | стандарт |
| Transport (dispatch) | RabbitMQ | per-message ack, DLQ, priority queues, delayed message plugin — естественный fit для job-шедулера |
| Сериализация payload | kotlinx-serialization JSON | |
| Cron-парсер | cron-utils (Java) | проверенная либа |
| DI | Koin | лёгкий, Kotlin-нативный |
| Lambda capture — shipped | K2 compiler plugin (IR rewrite) | стабильнее ASM, compile-time, без runtime-магии |
| Dashboard backend | KTOR (REST + WebSocket) | стандарт для Kotlin web |
| Dashboard frontend | Kotlin/JS + React 19 (Emotion) + Decompose навигация | общий `:core:shared` с бэком, type safety через границу |
| Тесты | Testcontainers | реальный PG + Rabbit для интеграции |
| Логи | SLF4J + Logback | стандарт |

---

## 3. Структура модулей

Gradle-модули режутся по **технической границе деплоя** (что в user app, что в scheduler-infra, что KMP js target). Внутри каждого модуля применяется **3-layer convention** (`api/domain/infrastructure` для backend, `data/domain/presentation` для UI) — там, где это имеет смысл. См. секцию 3.5 о trade-off "Gradle submodule vs пакет".

### 3.1. Gradle-модули — обзор

```
:core
├── :core:shared       — KMP (jvm + js). @Serializable API DTO, enums,
│                        value classes, публичные exceptions. Видимо с обеих сторон.
├── :core:backend      — JVM-only. BaseUseCase, runCatchingWithLogging,
│                        ApiResponse, Ktor *Handle helpers, TimeProvider,
│                        метрики (Micrometer), BaseValidation.
└── :core:frontend     — js-only. BaseComponent (Decompose), дизайн-система
                         (тема + UI-kit на Emotion), ApiClient (Ktor client),
                         WS subscriber base, хук useValue.

:storage-postgres      — StorageProvider impl + Flyway миграции
:transport-rabbit      — JobTransport impl, Rabbit topology, consumer/publisher
:engine-worker         — Worker pool, per-job heartbeat (ТОЛЬКО в user app)
:engine-infra          — Outbox publisher, recurring scheduler, fast-forward,
                         safety-net polling (ТОЛЬКО в scheduler-infra)
:dashboard-server      — KTOR REST + WebSocket (в составе scheduler-infra)
:dashboard-web         — React SPA (Kotlin/JS) с Decompose навигацией (bundle,
                         подключается к :dashboard-server как resources)
:standalone-runner     — main() + Docker image, биндит engine-infra + dashboard
:app                   — demo user app
```

Модули `:utils` из стартового каркаса убираем.

### 3.2. Деплой-роли

| Роль | Подключаемые модули | Где живёт |
|---|---|---|
| **User app** (enqueue + опц. worker) | `:core:shared`, `:core:backend`, `:storage-postgres`, `:transport-rabbit`, `:engine-worker` (опц.), handler-классы пользователя | container(s) пользователя |
| **scheduler-infra** | `:core:shared`, `:core:backend`, `:core:frontend`*, `:storage-postgres`, `:transport-rabbit`, `:engine-infra`, `:dashboard-server`, `:dashboard-web`, `:standalone-runner` | наш Docker image (`taskscheduler/infra:latest`) |

*`:core:frontend` подключается транзитивно через `:dashboard-web` (как KMP js target).

**Ключевой инвариант:** handler-классы пользователя — ВСЕГДА в user app, НИКОГДА в infra container. Infra container не знает про типы payload — оперирует на уровне `payload_type` (строка) + `payload_json`.

### 3.3. 3-layer convention: где какие слои

3-layer — внутренняя пакетная организация Gradle-модулей. Слой добавляется только когда нужен.

| Gradle-модуль | api | domain | infrastructure | Где |
|---|---|---|---|---|
| `:core:*` | — | — | — | не функциональный модуль, без слоёв |
| `:storage-postgres` | — | `StorageProvider` interface, доменные модели (Job/JobEvent/JobDependency/RecurringJob), репо-контракты | Exposed `Table` objects, `*RepositoryImpl`, Flyway migrations, мапперы | везде |
| `:transport-rabbit` | — | `JobTransport` interface, модели `TransportMessage` | Rabbit topology declare, `PublisherImpl`, `ConsumerImpl`, DLQ handlers | везде |
| `:engine-worker` | — | UseCases (`PickupJobUseCase`, `HeartbeatUseCase`, `CompleteJobUseCase`), модели `WorkerLease` | реализация worker pool, heartbeat loop, координация storage↔transport | user-app |
| `:engine-infra` | — | UseCases (`PublishOutboxUseCase`, `ScanRecurringUseCase`, `FastForwardDepsUseCase`, `SafetyNetPollUseCase`, `RetentionCleanupUseCase`) | реализации loop'ов, leader election, NOTIFY listener | scheduler-infra |
| `:dashboard-server` | **полный** (`dto/`, `validations/`, `descriptions/`, `extractors/`, `mappers/`, `routes/`) | UseCases для дашборда (`ListJobsUseCase`, `GetJobDetailUseCase`, `RetryJobUseCase`, `PauseTypeUseCase`, ...), репо-контракты | реализации запросов к storage, WS-subscriber на PG LISTEN/NOTIFY | scheduler-infra |
| `:dashboard-web` | — | `data/` + `domain/` + `presentation/` (см. 3.4) | — | scheduler-infra (как статика) |
| `:standalone-runner` | — | — | `Application.kt`, `configureScheduler()`, Koin `@KoinApplication`, Docker image | scheduler-infra entrypoint |

**Правила (взяты из основного проекта):**
- `api/` присутствует только в `:dashboard-server` — единственный модуль, выставляющий HTTP/WS наружу. Использует 6-шаговый pipeline `extractor → validation → mapper.toModel → usecase.invoke → mapper.toDto → response` через `*Handle` хелперы из `:core:backend`.
- `domain/` всегда присутствует в функциональных модулях. Чистые контракты, без зависимостей от Ktor/Exposed/AMQP — те живут в `infrastructure/`.
- `infrastructure/` отсутствует у модулей без внешних адаптеров (у нас таких нет — все модули касаются БД/Rabbit/HTTP).
- **Правило "1 функция репо ↔ 1 UseCase"** применяется только в `:dashboard-server` (CRUD-семантика endpoint-ов). В `:engine-worker`/`:engine-infra` use case'ы крупнее — pipelines/loops/batch-операции, правило 1:1 здесь неестественно и не применяется.
- **Один смысловой репо/маппер/валидатор — один класс**, не монолит. `JobsRepository` + `RecurringJobsRepository` + `WorkersRepository`, а не один `DashboardRepository`.

### 3.4. UI-структура `:dashboard-web`

Layers `data/domain/presentation` (как в основном проекте), но с поправкой на **Decompose** для навигации (см. секцию 15 о выборе).

```
dashboard-web/
├── data/
│   ├── network/         — ApiClient (Ktor client), endpoint функции
│   ├── websocket/       — WS subscriber для firehose событий
│   ├── repositories/    — *RepositoryImpl (реализации domain-репо)
│   └── mappers/         — API DTO (из :core:shared) ↔ UI domain model
├── domain/
│   ├── models/          — UI-доменные модели (если отличаются от shared DTO)
│   ├── repositories/    — интерфейсы репо
│   └── usecases/        — UseCase'ы (ObserveJobsListUseCase, RetryJobUseCase, ...)
└── presentation/
    ├── root/
    │   ├── RootComponent.kt          — interface + sealed Config (Decompose)
    │   ├── DefaultRootComponent.kt   — childStack(...) + создание child component'ов
    │   └── RootContent.kt            — React FC, рендерит активного child стека
    ├── screens/
    │   ├── joblist/
    │   │   ├── JobListComponent.kt        — interface: val model: Value<Model>; intent-функции
    │   │   ├── DefaultJobListComponent.kt — реализация (роль ViewModel)
    │   │   └── JobListContent.kt          — React FC, читает useValue(component.model)
    │   ├── jobdetail/
    │   ├── recurring/
    │   ├── workers/
    │   └── metrics/
    ├── components/      — переиспользуемые композаблы (JobStateBadge, JobProgressBar,
    │                      JsonViewer, PauseToggle, NodePinSelector)
    └── theme/           — Material 3 + цвета по job state
```

**Decompose-нюансы:**
- **Component** = бизнес-логика экрана + lifecycle + state preservation. Аналог ViewModel, но с навигацией внутри. Получает `ComponentContext` в конструктор (lifecycle/state/instanceKeeper).
- **`Value<T>`** — observable Decompose-овский, замена `StateFlow` на стороне UI. В React-компоненте: `useValue(component.model)`.
- **ChildStack** — стек навигации, sealed class `Config` описывает экраны (`Config.JobList`, `Config.JobDetail(id)`, ...). **Глубина стека обязана совпадать с глубиной браузерной истории**: `WebHistoryController` зеркалит сокращение стека относительным `history.go(новая − старая)`, поэтому стек мельче адресной строки превращает переход в раздел в «шаг назад». На старте стек поэтому строится из `webHistoryController.historyPaths` (`DefaultRootComponent.restoreStackFromHistory`), а не из одного `window.location.pathname` — `history.state` переживает F5, и контроллер по этой причине пропускает пересев истории. **Конфигурации в стеке обязаны быть уникальными**: дубликат — не «отменённая навигация», а исключение, которое навсегда травит Relay навигации (`Can't process the event due to a previous failure`), после чего молча умирают все клики до перезагрузки страницы. Поэтому переход на джобу идёт только через `pushToFront` (единая точка — `DefaultRootComponent.openJob`), а разделы верхнего уровня — через `replaceAll`; голый `push` в этом модуле не используется.
- DI: Koin резолвится внутри `DefaultXxxComponent` через конструктор (`get()` из global Koin) — Component сам пробрасывает зависимости в state.
- `viewmodels/` и `state/` отдельных папок **нет** — нативно для Decompose Component играет роль ViewModel, а `data class Model` — nested в interface Component.

**Один файл = один Component**: `JobListComponent.kt` содержит `interface JobListComponent { val model: Value<Model>; data class Model(...); fun onRetry(...) }`; `DefaultJobListComponent.kt` — реализация. Симметрично правилу основного проекта "разделение по смысловым группам".

### 3.5. Trade-off: Gradle submodule vs пакет

В основном проекте (`cs.trade.*`) модули — это **подпакеты одного Gradle-модуля**. У нас разделение **Gradle submodule** обусловлено тремя реальными причинами:

1. **Разный classpath по деплою.** `user-app` подключает `:engine-worker` (выполняет handler-ы), но не подключает `:engine-infra` (там outbox/recurring/dashboard) — handler-классы пользователя не должны утечь в infra-образ.
2. **Разный target compile.** `:core:shared` — KMP (jvm + js). `:dashboard-web` — js-only. `:engine-*` — jvm-only. Это технически разные source sets с разными compiler plugins.
3. **Independent versioning потенциально.** Если будет публикация на Maven Central — каждый модуль имеет свой artifactId.

Внутри одного Gradle-модуля разделение на пакеты `api/domain/infrastructure` — чистая convention, проверяется на code-review (никаких Module dependency analyser tool в MVP — overkill).

---

## 4. Архитектурные решения и их обоснования

### 4.1. Outbox pattern для consistency PG↔Rabbit

При enqueue делаем в одной транзакции:
```sql
INSERT INTO job (...);
INSERT INTO outbox (job_id, routing_key, payload, ...);
COMMIT;
```

Отдельный background publisher читает outbox с unpublished записями, шлёт в Rabbit, помечает `published_at`. **At-least-once delivery в Rabbit без 2PC.**

**Альтернатива (отвергнута):** публиковать сразу после COMMIT, полагаться на polling safety-net. Минус — окно, в котором job в PG но не в Rabbit, может затянуться.

### 4.2. Split deployment: user app workers + dedicated infra container

**Архитектура состоит из двух типов процессов** (см. секцию 14 для детального описания):

1. **User app container(s)** — `1..N` реплик. Embed-ит library (`schedulerWorkerModule`), выполняет handler-классы пользователя. Скейлится горизонтально под нагрузку.
2. **scheduler-infra container** — `1` реплика (наш Docker image). Запускает background-задачи (outbox publisher, recurring scheduler, fast-forward, safety-net polling) + dashboard. Не знает про user код. Restart-on-fail при падении.

**Что это даёт:**
- Чистое разделение: handler-код vs scheduler-инфраструктура
- Workers (user app) скейлятся независимо от инфры
- Dashboard всегда доступен из своего контейнера
- Infra-логика deploy-ится отдельно от user app

**Многонодная координация для user app:**
- Worker heartbeat + `locked_until` для orphan recovery (упал воркер → другой подберёт через ~30с-1мин)
- Optimistic locking через `version` column во всех update-ах
- Никаких in-memory очередей как source of truth

**Координация для infra container:** single replica → не нужны distributed locks (`pg_try_advisory_lock` всё равно ставим как future-proof для multi-replica в Phase 2+). На время restart (~10с) jobs накапливаются в outbox в PG, после restart catch-up.

### 4.3. Hybrid API: explicit + function references в MVP, lambda через KSP позже

**MVP даёт два способа:**

1. Explicit handlers (регистрация через Koin — см. секцию 12):
```kotlin
@Serializable data class SendEmail(val userId: Long, val template: String) : Job

@Single(binds = [JobHandler::class])
@JobType(SendEmail::class)
class SendEmailHandler(private val mailer: Mailer) : JobHandler<SendEmail> {
    override suspend fun execute(job: SendEmail) = mailer.send(job.userId, job.template)
}

scheduler.enqueue(SendEmail(123, "welcome"))
```

2. Function references (компромисс — без ASM, без KSP):
```kotlin
scheduler.enqueue(mailer::send, 123L, "welcome")
// target (mailer) — Koin singleton, resolved by Koin при выполнении
```

**Shipped (см. 21.9):** полный lambda capture — но через **K2 compiler plugin** (IR-переписывание call-site), не KSP (KSP не умеет переписывать call-site). `enqueueLambda { mailer.send(123, "welcome") }` на этапе компиляции транслируется в эквивалент function-ref enqueue. Стабильнее JobRunr'овского ASM-подхода.

### 4.4. Kotlin/JS + React для UI

**Плюсы:**
- Общий `:core:shared` модуль (KMP) — `@Serializable` типы шарятся между бэкендом (REST responses) и фронтом (UI state). Type safety через всю границу.
- Один язык по всему стеку.
- Reactive через Flow -> live updates job-ов.
- Рендер в DOM: настоящие `<table>` / `<button>` / `<input>` -> нативный скроллинг, поиск по странице, доступность и клавиатура работают без нашего участия.
- Bundle ~1.7 МБ вместо ~13 МБ у прежней Compose/Wasm-сборки (см. 15.8).

**Минусы (приняли):**
- Дизайн-систему (тема, контролы, таблицы) держим свою — готовый Material-компонентный набор не берём (см. 15.3).
- Версия `kotlin-wrappers` жёстко связана с версией Kotlin — обновлять только парой (см. 15.2).

**История:** до этого дашборд был на Compose Multiplatform (Wasm). Переехали на React ради размера бандла и DOM-семантики; Decompose-слой (навигация + стейт экранов) при переезде не менялся.

### 4.5. Timeout: 3-уровневая конфигурация, дефолт 5 минут

```kotlin
// 1. Глобальный дефолт
Scheduler.build { defaultJobTimeout = 5.minutes }

// 2. На handler-е (аннотация)
@JobTimeout(minutes = 30)
class HeavyReportHandler : JobHandler<HeavyReport>

// 3. Override на enqueue
scheduler.enqueue(SendEmail(...), timeout = 10.seconds)
```

Приоритет: enqueue > аннотация > глобальный дефолт.

При истечении: `kotlinx.coroutines.withTimeout(...)` → `TimeoutCancellationException` → job → `AWAITING_RETRY` с причиной `TIMEOUT` (если есть попытки) или `FAILED`.

JobRunr по дефолту без timeout — отвергнуто, зависшие handlers реальная проблема.

---

## 5. State machine

```
                  ┌─────────────────────┐
                  │   AWAITING_DEPS     │  ← job создан с after(...)
                  └──────────┬──────────┘
                             │ (все parents SUCCEEDED)
                             ▼
   ┌────────────┐    ┌─────────────────┐
   │ SCHEDULED  │───▶│    ENQUEUED     │  ← в Rabbit, ждёт воркера
   │ (future)   │    └────────┬────────┘
   └────────────┘             │ (worker берёт lock)
                              ▼
                     ┌─────────────────┐
                     │   PROCESSING    │  ← locked_by=node-X, locked_until=...
                     └────┬───────┬────┘
                          │       │
                ok        │       │ exception / timeout
                ┌─────────┘       └─────────┐
                ▼                           ▼
        ┌─────────────┐            ┌────────────────┐
        │  SUCCEEDED  │            │ AWAITING_RETRY │ (attempts < max)
        └─────────────┘            └────────┬───────┘
                                            │ (scheduled_at = now+backoff)
                                            ▼
                                       SCHEDULED → ENQUEUED

                                  (attempts == max)
                                            │
                                            ▼
                                     ┌─────────────┐
                                     │   FAILED    │
                                     └─────────────┘
```

Плюс `CANCELLED` (вручную через dashboard или из-за `on_failure = CANCEL_CHILD`).

---

## 6. Схема БД

```sql
job (
    id              UUID PK,
    state           TEXT,              -- AWAITING_DEPS | SCHEDULED | ENQUEUED | PROCESSING | SUCCEEDED | FAILED | AWAITING_RETRY | CANCELLED
    queue           TEXT,              -- 'default', 'email', 'heavy'
    priority        INT,
    payload_type    TEXT,              -- FQN sealed-class | function-ref descriptor
    payload_json    JSONB,
    scheduled_at    TIMESTAMPTZ,
    attempts        INT DEFAULT 0,
    max_attempts    INT DEFAULT 3,
    timeout_seconds INT,                -- NULL = дефолт scheduler-а
    locked_by       TEXT NULL,
    locked_until    TIMESTAMPTZ NULL,
    pending_deps    INT DEFAULT 0,
    version         INT DEFAULT 0,      -- optimistic locking
    idempotency_key TEXT NULL,           -- для enqueueOnce (см. 17.4)
    target_node     TEXT NULL,           -- node-pinning (см. 22.2)
    target_tag      TEXT NULL,           -- tag-based routing (см. 22.2)
    progress        REAL NULL,           -- 0.0..1.0 (см. 22.3)
    progress_msg    TEXT NULL,
    progress_updated_at TIMESTAMPTZ NULL,
    duration_ms     BIGINT NULL,         -- вычисляется при SUCCEEDED/FAILED (см. 22.4)
    started_at      TIMESTAMPTZ NULL,    -- для duration_ms
    created_at, updated_at
)

job_type_pause (                         -- для UI pause/unpause job types (см. 22.1)
    payload_type  TEXT PRIMARY KEY,
    paused_since  TIMESTAMPTZ,
    paused_by     TEXT,                  -- actor из dashboard
    reason        TEXT NULL
)

-- cancellation для PROCESSING jobs (см. 22.7)
ALTER TABLE job ADD COLUMN cancel_requested_at TIMESTAMPTZ NULL;
ALTER TABLE job ADD COLUMN cancel_requested_by TEXT NULL;       -- actor

-- context propagation: MDC + OTel traceparent (см. 22.11)
ALTER TABLE job ADD COLUMN context_json JSONB NULL;             -- {mdc, traceparent, tracestate}

-- dedup для DAG dependency edges (см. 22.10)
-- job_dependency PRIMARY KEY уже (parent_id, child_id), INSERT ON CONFLICT DO NOTHING
-- UNIQUE partial index для enqueueOnce
CREATE UNIQUE INDEX job_idempotency_key_active_idx ON job (idempotency_key)
  WHERE state IN ('AWAITING_DEPS', 'SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY')
    AND idempotency_key IS NOT NULL;

idempotency_log (                       -- для IdempotencyStore (см. 17.3)
    job_id      UUID,
    action      TEXT DEFAULT 'default',
    occurred_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, action)
)

job_dependency (
    parent_id    UUID,
    child_id     UUID,
    on_failure   TEXT,                  -- PROPAGATE_FAILURE | CANCEL_CHILD | IGNORE
    PK (parent_id, child_id)
)

recurring_job (
    id              TEXT PK,            -- человеко-читаемое имя
    cron            TEXT,
    timezone        TEXT NULL,          -- IANA TZ name (Europe/Berlin); NULL = UTC
    misfire_policy  TEXT DEFAULT 'CATCH_UP_ONE',   -- SKIP | CATCH_UP_ONE | CATCH_UP_ALL
    queue           TEXT,
    priority        INT DEFAULT 0,      -- 0..10, копируется в job при триггере
    target_node     TEXT NULL,          -- node-pinning, копируется в job
    target_tag      TEXT NULL,
    payload_type    TEXT,
    payload_json    JSONB,
    last_triggered_at  TIMESTAMPTZ,
    next_trigger_at TIMESTAMPTZ,
    enabled         BOOL
)

outbox (
    id           BIGSERIAL PK,
    job_id       UUID REFERENCES job(id) ON DELETE CASCADE,
    routing_key  TEXT,
    priority     INT DEFAULT 0,         -- 0..10, копируется из job при INSERT
    delay_ms     BIGINT DEFAULT 0,      -- для x-delay header при publish (delayed exchange)
    created_at   TIMESTAMPTZ,
    published_at TIMESTAMPTZ NULL
)

worker (
    node_id        TEXT PK,
    last_heartbeat TIMESTAMPTZ,
    host           TEXT,
    started_at     TIMESTAMPTZ,
    in_flight_count INT
)

job_event (                              -- audit log + history для dashboard
    id          BIGSERIAL PK,
    job_id      UUID REFERENCES job(id) ON DELETE CASCADE,
    event_type  TEXT,                    -- ENQUEUED, STARTED, SUCCEEDED, FAILED, RETRY, TIMEOUT, CANCELLED,
                                         -- MANUAL_RETRY, MANUAL_CANCEL, MANUAL_DELETE, MANUAL_TRIGGER
    prev_state  TEXT,
    new_state   TEXT,
    actor       TEXT NULL,                -- для MANUAL_* events: username / system identity, NULL для системных
    error_msg   TEXT NULL,
    error_stack TEXT NULL,
    occurred_at TIMESTAMPTZ
)
```

**FK ON DELETE CASCADE для retention** (см. секцию 18):
- `job_event.job_id`, `job_dependency.parent_id`, `job_dependency.child_id`, `outbox.job_id` → `job(id) ON DELETE CASCADE`
- `idempotency_log` — **БЕЗ FK** (намеренно, см. 18.4)

Ключевые индексы (черновик):
- `job(state, scheduled_at)` — для воркер-pickup
- `job(state, locked_until)` — для orphan recovery
- `outbox(published_at)` partial WHERE `published_at IS NULL`
- `job_event(job_id, occurred_at)`

---

## 7. Критичные flows

### 7.1. Enqueue

Транзакция:
```sql
BEGIN;
  INSERT INTO job (state, payload_type, payload_json, ...) VALUES (...);
  -- если есть deps:
  INSERT INTO job_dependency ...;
  -- если state=ENQUEUED (нет deps, не scheduled):
  INSERT INTO outbox (job_id, routing_key, payload, ...);
  INSERT INTO job_event (event_type=ENQUEUED, ...);
COMMIT;
```

Background outbox publisher (постоянная корутина):
```
loop:
  SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 100
  for each row:
    rabbit.publish(routing_key, payload)
    UPDATE outbox SET published_at = now() WHERE id = row.id
  sleep 100ms
```

### 7.2. Worker pickup

```
on rabbit_message(job_id):
  UPDATE job
    SET state='PROCESSING', locked_by=:me, locked_until=now()+5min, attempts=attempts+1, version=version+1
    WHERE id=:job_id AND state='ENQUEUED'
    RETURNING *
  if 0 rows: ack rabbit; continue  -- другой воркер забрал

  start heartbeat coroutine: every 30s UPDATE locked_until=now()+5min

  try {
    withTimeout(job.timeout) { handler.execute(payload) }
    UPDATE job SET state='SUCCEEDED', locked_by=NULL, version=version+1
    INSERT INTO job_event (SUCCEEDED)
    on_job_succeeded(job_id)   -- обработать dependents (см. 7.4)
  } catch (TimeoutCancellationException | Exception e) {
    if attempts < max_attempts:
       backoff = exp_backoff(attempts)
       UPDATE job SET state='AWAITING_RETRY', scheduled_at=now()+backoff, locked_by=NULL, version=version+1
       INSERT INTO job_event (RETRY)
       -- delayed message в Rabbit (или ждём safety-net polling)
    else:
       UPDATE job SET state='FAILED', locked_by=NULL, version=version+1
       INSERT INTO job_event (FAILED)
       on_job_failed(job_id)   -- обработать dependents
  }

  ack rabbit
```

### 7.3. Safety-net polling

Раз в 30с на каждом ноде:
- `SELECT id FROM job WHERE state='ENQUEUED' AND created_at < now() - interval '1 min'` → переотправить в Rabbit (через outbox)
- `UPDATE job SET state='ENQUEUED', locked_by=NULL WHERE state='PROCESSING' AND locked_until < now()` → orphan recovery

### 7.4. Dependency completion

При SUCCEEDED парента:
```sql
WITH children AS (
  SELECT child_id FROM job_dependency WHERE parent_id = :parent
)
UPDATE job
SET pending_deps = pending_deps - 1,
    state = CASE WHEN pending_deps - 1 = 0 THEN 'ENQUEUED' ELSE 'AWAITING_DEPS' END,
    version = version + 1
WHERE id IN (SELECT child_id FROM children);
-- для перешедших в ENQUEUED → INSERT INTO outbox
```

При FAILED парента: для каждого child из `job_dependency`:
- `on_failure = PROPAGATE_FAILURE` → child → FAILED
- `on_failure = CANCEL_CHILD` → child → CANCELLED
- `on_failure = IGNORE` → как при SUCCEEDED (decrement pending_deps)

### 7.5. Recurring scheduler

Каждый нод раз в 30с:
```kotlin
val lockKey = hashOf("recurring-scheduler-leader")
if (pgAdvisoryLockTry(lockKey)) {
    try {
        val due = selectRecurringWhereNextTriggerAtLte(now)
        for (r in due) {
            scheduler.enqueueAt(r.payload, scheduledAt = r.next_trigger_at, queue = r.queue)
            update next_trigger_at = cronUtils.nextExecution(r.cron, after = now)
            update last_triggered_at = now
        }
    } finally {
        pgAdvisoryUnlock(lockKey)
    }
}
```

В каждый момент только один нод leader → нет дубликатов.

---

## 8. Публичный API

### 8.1. Сборка scheduler

Регистрация через Koin DSL — детали в секции 12. Минимальный пример:

```kotlin
fun main() {
    startKoin {
        modules(
            // user modules (через Koin Annotations или DSL)
            AppModule().module,

            // scheduler modules (DSL — нужна builder lambda для конфига)
            schedulerCoreModule {
                nodeId = "app-1"
                defaultJobTimeout = 5.minutes
                defaultMaxAttempts = 3
                retryPolicy = ExponentialBackoff(1.seconds, 1.hours, multiplier = 2.0)
            },
            schedulerPostgresModule {
                dataSource = get<HikariDataSource>()
                runMigrations = true
            },
            schedulerRabbitModule {
                connectionFactory = get<ConnectionFactory>()
                queues = listOf("default", "email", "heavy")
            },
            schedulerWorkerModule {           // опционально — без него только enqueue
                concurrency = 10
                queues = listOf("default", "email")
            }
        )
    }

    val scheduler = GlobalContext.get().get<Scheduler>()
    scheduler.start()
    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { scheduler.stop() } })
}
```

### 8.2. Enqueue

```kotlin
// explicit
scheduler.enqueue(SendEmail(123, "welcome"))

// scheduled
scheduler.scheduleAt(SendEmail(123, "reminder"), Instant.parse("2026-06-01T10:00:00Z"))

// function reference
scheduler.enqueue(mailer::send, 123L, "welcome")

// с опциями
scheduler.enqueue(HeavyReport(...), EnqueueOptions(
    queue = "heavy",
    priority = 10,
    timeout = 30.minutes,
    maxAttempts = 5
))
```

### 8.3. Chains (последовательно)

```kotlin
scheduler.chain(
    SendEmail(123, "step1"),
    UpdateAnalytics(123),
    NotifySlack("done")
)
// эквивалент: B зависит от A, C зависит от B (PROPAGATE_FAILURE по дефолту)
```

### 8.4. Barriers (твой кейс прогрева кэша)

```kotlin
val w1 = scheduler.enqueue(LoadProductCache())
val w2 = scheduler.enqueue(LoadUserCache())

scheduler.enqueue(StartPricingEngine()) {
    after(w1, w2)
    onAnyFailure = OnFailure.CANCEL_CHILD
}
```

### 8.5. Recurring

```kotlin
scheduler.recurring(
    id = "daily-report",
    cron = "0 9 * * *",                              // каждый день в 9:00
    timezone = TimeZone.of("Europe/Berlin"),         // опционально, дефолт UTC
    misfirePolicy = MisfirePolicy.CATCH_UP_ONE,      // опционально, дефолт CATCH_UP_ONE
    queue = "reports",
    priority = 5,
    targetTag = "reports-node",                      // опционально, node-pinning (см. 22.2)
    job = DailyReport()
)
```

**Misfire** — если infra-container был down и cron-trigger пропущен:
- `SKIP` — ничего не запускаем, следующий в нормальное время
- `CATCH_UP_ONE` (default) — запускаем 1 раз (догнать), остальные пропускаем
- `CATCH_UP_ALL` — выполняем все пропущенные (для billing-snapshots и т.п. где каждый run важен)

Реализация (`FireDueRecurringJobsUseCase` + `CronExpr.catchUpPlan`): для `CATCH_UP_ALL`
создаётся по одной job на каждый пропущенный cron-слот в `[nextTriggerAt, now]` — все N
INSERT'ов под одним CAS на `last_triggered_at` (проигравшая реплика откатывает всю пачку).
Ограничение `MAX_CATCH_UP_PER_TICK` (500) на тик: при превышении `next_trigger_at`
остаётся на первом непогашенном слоте, остаток догоняется на следующих тиках (не теряется,
не флудит одну транзакцию). `SKIP` сейчас ведёт себя как `CATCH_UP_ONE` — строгий
«skip-if-missed» требует downtime-tracking, которого пока нет.

**Timezone** — IANA TZ name (`Europe/Berlin`, `America/New_York`); NULL = UTC. cron-utils умеет per-execution TZ.

---

## 9. Dashboard

Живёт только в scheduler-infra container (см. секцию 14). KTOR-сервер с REST + WebSocket, отдаёт React SPA как статику.

### 9.1. REST endpoints

```
# Jobs
GET    /api/jobs?state=ENQUEUED,PROCESSING&queue=email&q=user:123
                 &from=...&to=...&page=0&size=50&sort=created_at:desc
       → { items: [JobView], total, page, size }

GET    /api/jobs/{id}
       → { job: JobView, events: [JobEvent], deps: { parents: [...], children: [...] } }

POST   /api/jobs/{id}/retry           → 202   (см. 9.5 — attempts сбрасываются)
POST   /api/jobs/{id}/cancel          → 202   (любое не-терминальное → CANCELLED)
DELETE /api/jobs/{id}                 → 204   (soft-delete)

# Recurring
GET    /api/recurring                 → [...]
POST   /api/recurring/{id}/trigger    → 202   (immediate job из payload)
POST   /api/recurring/{id}/enable
POST   /api/recurring/{id}/disable
POST   /api/recurring                 → create (опц.)
DELETE /api/recurring/{id}

# Workers
GET    /api/workers
       → [{ node_id, host, started_at, last_heartbeat, in_flight_count, alive }]
       (alive = last_heartbeat < 1min ago)

# Stats / overview
GET    /api/stats/overview
       → { 
           jobs: { enqueued, processing, awaiting_retry, succeeded_24h, failed_24h },
           queues: [{ name, depth, in_flight, throughput_per_min }],
           workers: { alive, total }
         }

# Live events stream
WS     /api/events
```

### 9.2. WebSocket: firehose + invalidation signals

**По умолчанию — все события всем подключённым клиентам** (общий firehose-сокет, клиент фильтрует локально). **Subscribe-with-query — shipped:** клиент сужает поток серверно, передав query-параметры на WS-апгрейде — `/api/ws/events?jobId=…&queue=…&type=…&eventType=…` (повторяемые; пусто = всё). Сервер строит [EventFilter] (`core/shared`; конъюнкция по измерениям, дизъюнкция внутри; `matches()` через exhaustive `when` по подтипам) и форвардит только подходящие события. Дашборд держит общий широкий сокет (JobList реагирует почти на все типы событий), а JobDetail открывает узкую подписку `?jobId=<id>` через `EventStream.subscribe(filter)` для прогресса своей джобы.

Сообщения — **invalidation signals**, не полные снапшоты состояния. Клиент дотягивает детали GET-запросом если нужно.

Формат (compact JSON):
```json
{"t":"job_state","id":"<uuid>","from":"PROCESSING","to":"SUCCEEDED","queue":"email","at":"2026-..."}
{"t":"job_created","id":"<uuid>","queue":"email","type":"SendEmail"}
{"t":"worker_join","node":"app-1","host":"..."}
{"t":"worker_leave","node":"app-2"}
{"t":"recurring_triggered","id":"daily-report","job_id":"<uuid>"}
```

### 9.3. PG LISTEN/NOTIFY как источник events

scheduler-код (user app workers) после каждого state transition:
```kotlin
storage.notify("scheduler_events", Json.encodeToString(event))
// → PG NOTIFY scheduler_events, '<json>'
```

dashboard-server (в infra container) держит один listener:
```kotlin
storage.listen("scheduler_events") { json ->
    val event = Json.decodeFromString<SchedulerEvent>(json)
    eventBus.emit(event)  // SharedFlow → broadcast всем WS-клиентам
}
```

PG `NOTIFY` доставляется всем `LISTEN` подключениям в кластере → бесплатный distributed pub/sub. Payload-limit 8KB → шлём только signals, не полные данные.

**Не trigger AFTER UPDATE** — explicit NOTIFY в коде даёт больше контроля над форматом payload и не зависит от схемы.

### 9.4. Auth DSL

```kotlin
schedulerDashboardModule {
    port = 8080
    auth {
        basic {
            username = "admin"
            password = System.getenv("DASHBOARD_PASSWORD")
                ?: error("Set DASHBOARD_PASSWORD")
        }
        // ИЛИ
        none()   // local dev only — логирует warning при старте
        // ИЛИ
        custom("my-ktor-auth")   // имя уже зарегистрированной install(Authentication)
    }
}
```

Три варианта: basic auth (default для prod), none() (local dev), custom (KTOR Authentication пользователя — для OAuth/JWT/session/whatever). JWT/OAuth встроенными не делаем — pluggable достаточно.

### 9.5. Retry semantics из dashboard

`POST /api/jobs/{id}/retry` для FAILED job-а:
```sql
UPDATE job 
SET state = 'ENQUEUED', 
    attempts = 0,                              -- ← сброс
    locked_by = NULL, locked_until = NULL,
    version = version + 1
WHERE id = :id AND state = 'FAILED'
RETURNING *;
-- + INSERT INTO outbox (job_id, routing_key, delay_ms=0)
-- + INSERT INTO job_event (event_type='MANUAL_RETRY', ...)
```

**Сброс attempts**: семантика "пользователь увидел ошибку, поправил, начинаем сначала". Иначе FAILED job с `attempts == max_attempts` нельзя было бы перезапустить ручным retry.

**Две кнопки (shipped).** [RetryMode] (`core/shared`) выбирает политику бюджета, проходит через `Scheduler.retry(jobId, by, mode)` → `JobRepository.manualRetry(..., mode)`:
- **Retry** — `POST /api/jobs/{id}/retry` (mode по умолчанию `FRESH_BUDGET`). `attempts = 0` → полный свежий бюджет. Event `MANUAL_RETRY`.
- **Retry +1** — `POST /api/jobs/{id}/retry?mode=once`. `attempts = max_attempts - 1`: следующий pickup поднимет счётчик до `max_attempts`, и worker-гейт `attempts < max_attempts` провалится в момент падения → **ровно одна** попытка, затем снова FAILED, без авто-ретрай-шторма. Event `MANUAL_RETRY_ONCE`. Семантика "похоже на транзиентный сбой, дай один шанс без полного бюджета". Детерминированно независимо от того, как job попал в FAILED (исчерпал бюджет ИЛИ non-retriable/schema-ошибка). UI — вторая (OutlinedButton) кнопка в JobDetail, только на FAILED. Один CAS-UPDATE; `max_attempts` иммутабелен после enqueue, поэтому чтение его в той же транзакции race-free.

### 9.6. Frontend (Kotlin/JS + React)

Подаётся dashboard-server-ом как статика. SPA с router-ом, все routes под `/`:

- **/jobs** — JobList (виртуализованная LazyColumn, фильтры по state/queue/search/range)
- **/jobs/{id}** — JobDetail (state timeline из `job_event`, payload viewer, retry/retry+1/cancel/delete/re-route actions, dependency graph)
- **/recurring** — RecurringList (cron-таблица, manual trigger, enable/disable, **Last run**: состояние текущего/последнего запуска + его прогресс; клик по строке открывает этот запуск)
- **/workers** — WorkerList (heartbeat, in-flight count, host, статус alive)
- **/** — Overview (`/api/stats/overview` cards + recent failures)
- **DependencyGraph — shipped** — транзитивный DAG-граф конкретного job-а (все достижимые предки/потомки), инлайн в JobDetail между Payload и Timeline. Сервер обходит компонент BFS в обе стороны по `JobDependencyRepository` (лимит 100 узлов, флаг `truncated`); `JobDetail.graph: JobGraph{nodes, edges, truncated}` заменил плоские списки `parents`/`children`. `DependencyGraph` рисует слоевую топологическую раскладку (longest-path layering + barycenter-упорядочивание): узлы — абсолютно спозиционированные карточки со `StateChip`, фокус-узел подсвечен, клик по узлу -> его detail; рёбра parent->child рисуются на SVG-слое под карточками со стрелками, цвет по `on_failure`. Граф показывается только если есть рёбра (одиночная джоба → секция скрыта). Полоса прокручивается по горизонтали и при открытии **сама доматывает до фокус-узла** (он встаёт к левому краю, слева остаётся один `LEVEL_GAP` — видно входящую стрелку): цепочка из десятка шагов шире панели в разы, и без этого открытая джоба оказывалась за правым краем.

**Last run на Recurring — shipped.** Каждое определение показывает свой живой запуск (или последний
завершённый): `StateChip`, для живого — сколько он уже идёт и полоса прогресса, для никогда не
запускавшегося — «never run». Клик по строке открывает именно этот job, поэтому «что оно делает
прямо сейчас» — один клик, а не поиск по JobList с фильтром по типу.

Связь `job → recurring_job` даёт колонка `job.recurring_id` (миграция V9). До неё единственным
следом был производный `idempotency_key` (`recurring:<id>`), и только при overlap-политиках
SKIP / REPLACE — дефолтная ALLOW пишет `NULL`, так что определение не могло найти собственные
запуски. Проставляют её оба пути запуска: плановый (`FireDueRecurringJobsUseCase`) и ручной
«Run now» (`DefaultScheduler.triggerRecurringNow`).

Листинг остаётся двумя запросами на экран: определения + `findLatestRunsByRecurringIds` для всей
страницы разом (`DISTINCT ON (recurring_id)`, живые сортируются раньше терминальных, затем
`created_at DESC`). Per-row запрос был бы N+1 по таблице со всеми когда-либо запущенными job.
Колонка nullable и не бэкфилится: строки, созданные до V9, атрибутировать задним числом нечем —
определение показывает свою историю начиная со следующего запуска.

WS-клиент подключается один раз на `/api/events`, диспатчит по типу события в state локального ViewModel.

---

## 10. MVP scope vs Later

### MVP
- State machine + PG storage + Flyway schema
- Rabbit transport + outbox publisher + safety-net polling
- Recurring scheduler (advisory lock leader)
- DAG: chains + barriers (`after(...)`, `.then(...)`)
- Retry с exponential backoff
- Timeout (3 уровня)
- Dashboard backend: REST list/detail/retry/cancel + WebSocket events
- Dashboard web: JobList + JobDetail + RecurringList + WorkerList
- Demo `:app`

### Phase 2
- Lambda capture через KSP-плагин
- Priority queues (несколько Rabbit queues + worker affinity)
- Tags для jobs + поиск по тэгу
- DependencyGraph visualization в dashboard
- Метрики (Micrometer)

### Phase 3+
- Kafka transport (pluggable)
- MongoDB storage (pluggable)
- Spring/Kodein DI интеграции
- Multi-tenant (namespace isolation)
- Long-running jobs с прогрессом (`job.updateProgress(0.7f)`)

---

## 11. RabbitMQ topology and deployment

### 11.1. Деплой по средам

- **Local dev:** Docker Compose с PG + кастомным образом Rabbit (Rabbit 3.13 + `rabbitmq_delayed_message_exchange` plugin + management UI на 15672).
- **CI / интеграционные тесты:** Testcontainers с тем же кастомным образом.
- **Prod:** пользователь либы сам поднимает Rabbit. **Требование:** установлен plugin `rabbitmq_delayed_message_exchange`. Документируем явно в README.

### 11.2. Custom Rabbit image

`docker/rabbitmq/Dockerfile`:
```dockerfile
FROM rabbitmq:3.13-management-alpine
ARG PLUGIN_VERSION=3.13.0
RUN apt-get update && apt-get install -y curl && \
    curl -L -o $RABBITMQ_HOME/plugins/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez \
      https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez && \
    rabbitmq-plugins enable rabbitmq_delayed_message_exchange
```

В `docker-compose.yml` секция:
```yaml
rabbitmq:
  build: ./docker/rabbitmq
  ports: ["5672:5672", "15672:15672"]
  environment:
    RABBITMQ_DEFAULT_USER: scheduler
    RABBITMQ_DEFAULT_PASS: scheduler
  volumes: [rabbit_data:/var/lib/rabbitmq]
```

### 11.3. Topology

```
exchange: jobs.dispatch  (type: x-delayed-message, x-delayed-type: direct, durable)
  ├── rk="default" → q.default  (x-dead-letter-exchange: jobs.dlx, x-max-priority: 10)
  ├── rk="email"   → q.email    (same args)
  └── rk="heavy"   → q.heavy    (same args)

exchange: jobs.dlx  (type: direct, durable)
  └── rk=""        → q.dead-letter  (durable)
```

- Один exchange для immediate / scheduled / retry — отличает только header `x-delay`.
- DLX для случаев "не парсится / handler не зарегистрирован" → `q.dead-letter` для inspection через dashboard.
- `x-max-priority: 10` на queue — приоритеты внутри одной queue (API для priority — open question 12.2).

### 11.4. Message format

- Body: **ровно 16 сырых байт** — big-endian представление `job_id` (`Uuid.toByteArray()`),
  **не** текстовый UUID. Consumer проверяет длину строго: тело любого другого размера →
  `basicNack(requeue = false)` → DLX, handler не вызывается.
- Properties: `deliveryMode = 2` (persistent) + `priority` (0..10, значение зажимается в
  диапазон — см. 19.5).
- Header `x-delay`: миллисекунды задержки, **32-битный int** (ограничение
  delayed-message-plugin, потолок ~24 дня). Отсутствие заголовка = немедленная доставка.
  Значение всегда ≤ `fastForwardWindow` (24ч) — это и есть причина гибридной схемы 11.5.
- Полный payload загружается воркером из PG при pickup — single source of truth.

> **Источник истины по формату кадра — код, а не этот раздел:**
> `RabbitJobTransport.publish` / `consume` (`:transport-rabbit`) и тест
> `RabbitJobTransportTest` («malformed body is nacked without invoking handler»).
> До 2026-08 здесь было написано «UUID как UTF-8 строка, ~36 байт» — неверно с самого MVP;
> клиент, написанный по той строке, молча уезжал бы в dead-letter.

### 11.5. Scheduling strategy (hybrid: plugin + PG fast-forward)

| Delay job-а | Где живёт |
|---|---|
| `now` (immediate enqueue) | publish в `jobs.dispatch`, **без** `x-delay` |
| `< 24h` (scheduled / retry с коротким backoff) | publish в `jobs.dispatch` с `x-delay = delay_ms`, state=`ENQUEUED` |
| `≥ 24h` (далёкий scheduleAt, recurring на завтра+) | в PG: `state=SCHEDULED, scheduled_at=T`, **не публикуется** сразу |

**Fast-forward task** (раз в 5 минут на leader-ноде — pg_advisory_lock):
```sql
SELECT id, scheduled_at, queue, version
FROM job
WHERE state='SCHEDULED' AND scheduled_at < now() + interval '24 hours'
LIMIT 1000;
-- для каждого:
UPDATE job SET state='ENQUEUED', version=version+1
WHERE id=:id AND state='SCHEDULED' AND version=:v;
-- если updated: INSERT INTO outbox(job_id, routing_key=queue, delay_ms=(scheduled_at - now()))
```

Так Rabbit не нагружается миллионами long-delay сообщений (он не для этого), но короткие задержки получают миллисекундную точность.

### 11.6. Stale message handling

Если job был cancelled (`state=CANCELLED`) или уже выполнен (race с safety-net) пока сообщение лежало в delayed exchange — воркер при pickup делает:
```sql
UPDATE job SET state='PROCESSING', ... WHERE id=:id AND state='ENQUEUED' RETURNING *
```
0 rows → `basicAck` и игнорируем. Никакой отдельной "удалить из Rabbit при cancel" логики не нужно.

### 11.7. Connection management

- **Один** `Connection` на JVM-инстанс
- `automaticRecoveryEnabled = true`, `networkRecoveryInterval = 5s`, `requestedHeartbeat = 30s`
- Отдельный `Channel` для publisher (или per-thread через ThreadLocal)
- Отдельный `Channel` на каждого consumer (один на queue в `worker.queues`)
- `basicQos(prefetchCount = 10)` (конфигурируемо) — сколько jobs воркер берёт впрок
- `autoAck = false` — ack строго после успешной обработки

### 11.8. Client library

`com.rabbitmq:amqp-client` (официальный Java-клиент). Блокирующий API оборачиваем в coroutines: `publish` через `withContext(Dispatchers.IO)`, `basicConsume` callback переотправляет в worker scope через `launch`.

Spring AMQP и Reactor RabbitMQ отвергли — тянут лишние зависимости (Spring Context / Project Reactor) при наличии собственного coroutines-стека.

---

## 12. Koin integration

### 12.1. Roles разделения

Пользователь использует **Koin Annotations** (KSP) для собственного DI. Наша библиотека публикует **DSL-модули с builder lambda** (потому что конфиг — runtime). Koin спокойно совмещает оба стиля в одном `startKoin {}`.

### 12.2. Публичные scheduler-модули

| Модуль | Где используется | Что регистрирует |
|---|---|---|
| `schedulerCoreModule { ... }` | везде | `Scheduler`, `JobHandlerRegistry`, `RetryPolicy`, `SchedulerConfig` |
| `schedulerPostgresModule { ... }` | везде | `StorageProvider`. Флаг `runMigrations` — true только в infra container |
| `schedulerRabbitModule { ... }` | везде | `JobTransport`, Rabbit topology (idempotent declare) |
| `schedulerWorkerModule { ... }` | **user app** (опц.) | worker pool, consumer-ы Rabbit, per-job heartbeat |
| `schedulerInfraModule { ... }` | **scheduler-infra container** | outbox publisher, recurring scheduler, fast-forward, safety-net polling |
| `schedulerDashboardModule { ... }` | **scheduler-infra container** | KTOR routes для dashboard, WebSocket events |

**Типичные бандлы по ролям:**

| Роль | Подключаемые модули |
|---|---|
| User app (только enqueue) | core + postgres + rabbit |
| User app (enqueue + worker) | core + postgres + rabbit + worker |
| scheduler-infra container | core + postgres (runMigrations=true) + rabbit + infra + dashboard |

См. секцию 14 для детального описания деплоя.

### 12.3. Регистрация handler-ов пользователем

Стандартный Koin Annotations синтаксис + наша аннотация `@JobType`:

```kotlin
@Single(binds = [JobHandler::class])
@JobType(SendEmail::class)
class SendEmailHandler(
    private val mailer: Mailer
) : JobHandler<SendEmail> {
    override suspend fun execute(job: SendEmail) {
        mailer.send(job.userId, job.template)
    }
}
```

Две аннотации:
- `@Single(binds = [JobHandler::class])` — стандартный Koin, привязывает к интерфейсу для `getAll`
- `@JobType(SendEmail::class)` — наша, говорит scheduler-у "этот handler обрабатывает SendEmail"

Не комбинируем в одну (`@JobHandlerComponent`) потому что пришлось бы тащить собственный KSP-процессор. Две стандартные аннотации проще и прозрачнее.

### 12.4. JobHandler API

```kotlin
interface Job  // marker, наследуют payload data classes

@Retention(RUNTIME)
@Target(CLASS)
annotation class JobType(val value: KClass<out Job>)

interface JobHandler<T : Job> {
    suspend fun execute(ctx: JobContext, job: T)
    
    val retryPolicy: RetryPolicy?
        get() = null   // null = use global
    
    val defaultPriority: Int
        get() = 0      // 0..10; см. секцию 19
    
    suspend fun onFinalFailure(ctx: JobContext, job: T, error: Throwable) {}
}

interface JobContext {
    val jobId: UUID
    val attempt: Int                       // 1, 2, 3...
    val queue: String
    val enqueuedAt: Instant
    val maxAttempts: Int
    val parentJobIds: List<UUID>
    
    /** Throttled до раз в секунду — см. 22.3 */
    suspend fun updateProgress(progress: Float, msg: String? = null)
}
```

`ctx.jobId` — стабильный идемпотентный ключ для всех попыток одного job-а (см. секцию 17 о идемпотентности).

JobType извлекается через `handler::class.findAnnotation<JobType>()` при построении registry. Job inheritance — простой marker interface, payload — `@Serializable` data class.

### 12.5. Registry build

На `scheduler.start()`:
```kotlin
val handlers: List<JobHandler<*>> = koin.getAll<JobHandler<*>>()
val map: Map<KClass<out Job>, JobHandler<*>> = handlers.associateBy { handler ->
    handler::class.findAnnotation<JobType>()
        ?: error("${handler::class.simpleName} missing @JobType annotation")
}.mapKeys { it.value.value }
```

Конфликт (два handler-а для одного типа) → fail-fast с ясной ошибкой.

### 12.6. Lifecycle

**Базовый вариант — explicit:**
```kotlin
val scheduler = koin.get<Scheduler>()
scheduler.start()                                              // запуск workers, outbox publisher, recurring scheduler
Runtime.getRuntime().addShutdownHook(Thread {
    runBlocking { scheduler.stop(timeout = 30.seconds) }       // graceful drain
})
```

**Дополнительный вариант — KTOR plugin** (отдельный модуль `:scheduler-ktor`):
```kotlin
fun Application.module() {
    install(SchedulerPlugin)  // достаёт Scheduler из Koin, привязывает к ApplicationStarted / ApplicationStopping
    install(SchedulerDashboard) { /* dashboard routes */ }
}
```
Под капотом:
```kotlin
val SchedulerPlugin = createApplicationPlugin("Scheduler") {
    val scheduler: Scheduler by application.inject()
    on(MonitoringEvent(ApplicationStarted)) { scheduler.start() }
    on(MonitoringEvent(ApplicationStopping)) {
        runBlocking { scheduler.stop(timeout = 30.seconds) }
    }
}
```

Для KTOR-юзеров — меньше boilerplate. Для не-KTOR (Spring, plain main) — explicit start/stop.

### 12.7. Конфиг — почему builder lambda, а не `@Single`

Koin Annotations работает compile-time, не может принять runtime-значения. Builder lambda даёт:
- Конфигурация явная и в одном месте
- Тестируемость (можно поднять scheduler с моками в тесте без полного Koin context-а)
- Возможность вынести в `application.conf` / env vars / любой config-loader пользователя

Поэтому наши модули — функции `(SchedulerCoreConfig.() -> Unit) -> Module`, а не аннотированные классы.

---

## 13. Worker pool, concurrency, lifecycle

### 13.1. Per-queue scope с per-queue concurrency

Главное решение: **отдельный coroutine scope на каждую queue, со своим concurrency limit**. Не глобальный pool.

**Why:** разные queues существуют именно для разделения работы по характеру. `email` — лёгкие (concurrency=20), `heavy` — тяжёлые (concurrency=2). Глобальный pool позволил бы heavy задавить email.

```kotlin
schedulerWorkerModule {
    defaultConcurrency = 10                  // дефолт для queue без override
    shutdownTimeout = 30.seconds
    heartbeatInterval = 30.seconds
    lockDuration = 90.seconds                // = 3 × heartbeatInterval, запас на GC pause
    
    queue("default", concurrency = 10)
    queue("email",   concurrency = 20)
    queue("heavy",   concurrency = 2, prefetch = 4)
}
```

Под капотом:
```kotlin
class QueueRuntime(
    val queue: String,
    val scope: CoroutineScope,                // Dispatchers.IO + SupervisorJob() + CoroutineName
    val semaphore: Semaphore
)
```

`SupervisorJob` — упавший один job не отменяет весь scope queue-а.

### 13.2. Prefetch count

Rabbit prefetch = сколько unack'ed сообщений consumer держит "впрок". Естественный backpressure: если prefetch=10 и все 10 заняты — Rabbit не шлёт новых.

**Правило:** `prefetch >= concurrency`. По дефолту `prefetch = concurrency`. Override через `queue(..., prefetch = N)`.

### 13.3. Coroutine dispatcher — shipped (Phase 3)

Default `Dispatchers.IO` для всех queue (99% jobs — IO-bound). Per-queue override через `queue("…", dispatcher = …)` в `schedulerWorkerModule`:

```kotlin
schedulerWorkerModule {
    queue("cpu-heavy",
        concurrency = 4,
        dispatcher = Dispatchers.Default,        // CPU-bound jobs
    )
    queue("isolated-cache",
        concurrency = 1,
        dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    )
}
```

Только handler body выполняется на overridden dispatcher — Rabbit dispatch, PG pickup, outbox writes остаются на `Dispatchers.IO` через свои `withContext` обёртки в repo-слое. Реализация: `QueueConfig.dispatcher` добавляется в `handlerCtx` перед `withContext(handlerCtx) { handler.execute(...) }` в `WorkerPool.processLockedInner`.

### 13.4. Heartbeat и lock duration

**Per-job heartbeat включён с MVP.** Lock NOT привязан к job.timeout.

| Параметр | Значение |
|---|---|
| `heartbeatInterval` | 30s (дефолт) |
| `lockDuration` | 90s (3 × heartbeatInterval) |
| Перехват орфанов | через ~30s-1min после смерти worker-а |

**Эффективный heartbeat — один UPDATE на все in-flight jobs воркера:**
```sql
-- раз в heartbeatInterval (30s), один запрос
UPDATE job 
SET locked_until = now() + (lockDuration)::interval
WHERE locked_by = :node_id AND state = 'PROCESSING';
```

Минимальная нагрузка на БД независимо от количества параллельных jobs.

**Если worker замёрз (GC pause > lockDuration):**
1. `locked_until < now()` → safety-net polling на другом ноде через ≤30с перехватывает: `UPDATE job SET locked_by=NULL, state='ENQUEUED' WHERE state='PROCESSING' AND locked_until < now()` + переотправка через outbox в Rabbit
2. Замёрзший worker "просыпается", пытается завершить:
   ```sql
   UPDATE job SET state='SUCCEEDED' WHERE id=:id AND locked_by=:me AND version=:v
   ```
3. 0 rows → log warning, игнорируем (другой нод уже выполняет / выполнил)
4. **Job может выполниться дважды.** At-least-once гарантия, обходится идемпотентностью handler-а (см. open question).

### 13.5. Background tasks per node

Не всё запускается на каждой ноде. Зависит от подключенных Koin-модулей:

| Background task | На какой ноде | Зачем |
|---|---|---|
| Outbox publisher | **везде** | любая нода может enqueue → должна публиковать |
| Recurring scheduler (cron) | **везде** (один станет leader через `pg_try_advisory_lock`) | distributed coordination |
| Safety-net polling | **везде** | orphan recovery + missed enqueue catch-up |
| Fast-forward (≥24ч → delayed exchange) | **везде** (через advisory lock) | distributed coordination |
| Worker heartbeat (worker.last_heartbeat) | где есть worker module | dashboard "живые ноды" |
| Per-job lock heartbeat | где есть worker module | продление locked_until |
| Rabbit consumers | где есть worker module | приём jobs |

### 13.6. Graceful shutdown

```kotlin
suspend fun stop(timeout: Duration = 30.seconds) {
    // 1. Перестать брать новые
    transport.cancelAllConsumers()
    
    // 2. Остановить background tasks
    workerScope.cancel()
    
    // 3. Drain in-flight jobs (с timeout)
    withTimeoutOrNull(timeout) {
        queueScopes.values.flatMap { 
            it.scope.coroutineContext.job.children.toList() 
        }.joinAll()
    }
    
    // 4. Force cancel оставшихся (locked_until остаётся → orphan recovery подхватит)
    queueScopes.values.forEach { it.scope.cancel() }
    
    // 5. Закрыть Rabbit connection, отметить worker как остановленный
    transport.close()
    storage.unregisterWorker(nodeId)
}
```

**Force-cancelled jobs корректно мигрируют:**
- Coroutine отменена → CancellationException в handler-е
- Worker НЕ обновляет state (намеренно — мы не успели или умерли)
- Job остаётся `state=PROCESSING, locked_until=...`
- Через lockDuration (90s) другой нод видит истёкший lock → перехватывает → retry или выполняет

То же поведение что и при `kill -9`. **Heartbeat и shutdown работают одинаково и для graceful, и для hard kill.**

**Shutdown timeout 30s** — матчит k8s SIGTERM grace period по умолчанию. Не успевшие — выживут через orphan recovery.

### 13.7. Worker-only vs enqueue-only ноды

Подключая или не подключая `schedulerWorkerModule`:
- **Без `schedulerWorkerModule`:** нода только enqueue-ит (REST handlers, фоновая отправка событий). Outbox/recurring/safety-net/fast-forward работают, consumer-ов нет.
- **С `schedulerWorkerModule`:** нода и enqueue-ит, и выполняет.

Типичный prod setup: бэкенд-app без worker module + 3-5 отдельных worker-процессов с worker module. Скейлится независимо.

---

## 14. Deployment architecture

### 14.1. Two-container model

```
┌──────────────────────────────────────┐    ┌────────────────────────────────────┐
│   User app container(s)  (1..N)       │    │   scheduler-infra container  (1)   │
│                                       │    │   (наш Docker image)               │
│   ┌─────────────────────────────┐    │    │                                    │
│   │ User's KTOR/Spring/main app │    │    │   - Outbox publisher               │
│   │                              │    │    │   - Recurring scheduler (cron)     │
│   │ + scheduler library:         │    │    │   - Fast-forward (≥24ч)            │
│   │   • schedulerCoreModule      │    │    │   - Safety-net polling             │
│   │   • schedulerPostgresModule  │    │    │   - Dashboard backend (KTOR)       │
│   │   • schedulerRabbitModule    │    │    │   - Dashboard frontend (JS)        │
│   │   • schedulerWorkerModule    │    │    │   - Flyway migrations on start     │
│   │ + handler-классы пользователя│    │    │                                    │
│   └─────────────────────────────┘    │    │                                    │
└───────────┬───────────────────────────┘    └─────────────────┬──────────────────┘
            │                                                  │
            └──────────────┬──────────┬────────────────────────┘
                           ▼          ▼
                       Postgres    RabbitMQ
                       (shared)    (shared, с delayed-message plugin)
```

### 14.2. Что где живёт (детально)

| Задача | User app worker | scheduler-infra |
|---|---|---|
| Enqueue job (INSERT INTO job + outbox) | ✅ из application код | ❌ |
| Execute handler (выполнить пользовательский код) | ✅ | ❌ — нет handler-классов |
| Per-job lock heartbeat | ✅ (для своих in-flight) | ❌ |
| Worker heartbeat в `worker` таблице | ✅ | ❌ (но dashboard читает) |
| Rabbit consumer на queue | ✅ | ❌ |
| Outbox publisher (PG → Rabbit) | ❌ | ✅ |
| Recurring scheduler (cron → enqueue) | ❌ | ✅ |
| Fast-forward (≥24ч → delayed exchange) | ❌ | ✅ |
| Safety-net polling (orphan recovery + missed publish) | ❌ | ✅ |
| Flyway миграции | ❌ | ✅ при старте |
| Dashboard REST / WS / static | ❌ | ✅ |

### 14.3. Single replica + restart-on-fail

scheduler-infra — **single replica** в MVP. Обоснование:
- Все background-задачи выполняются на одной ноде → нет distributed coordination overhead
- При падении: k8s/docker-compose рестартит автоматически за ~5-10с
- Jobs не теряются: outbox/`SCHEDULED`/`PROCESSING` всё в PG, после restart catch-up
- Workers в user app продолжают выполнять in-flight jobs независимо

**Что страдает на время restart-а** (~5-10с):
- Новые jobs из outbox не публикуются в Rabbit (но уже в PG)
- Cron-recurring-jobs не триггерятся (если попадает на этот момент — выполнится при следующем запуске safety-net polling)
- Dashboard недоступен
- Orphan recovery приостановлена (но lock-и 90с → запас есть)

Всё некритично. HA через multi-replica + advisory-lock leader election — shipped (`LeaderElection`, см. историю 2026-05-26): gating для OutboxPublisher/RecurringScheduler/FastForwardTask/RetentionCleanup, release в shutdown.

### 14.4. Flyway migrations — infra container = "владелец схемы"

- scheduler-infra при старте запускает `Flyway.migrate()` → блокируется пока миграции не применятся
- User app при старте делает `Flyway.info()` (или эквивалент): сравнивает версию схемы с ожидаемой библиотекой
  - Если схема старее → `error("Schema version X required, found Y. Update scheduler-infra first.")`. Fail-fast.
  - Если схема новее → ok (forward-compatible, новые поля игнорируются)
- Деплой-порядок: сначала обновляем scheduler-infra → потом user app. Стандартный pattern "API contract first"

### 14.5. Конфигурация infra container

Через env vars:

| Env var | Дефолт | Что |
|---|---|---|
| `POSTGRES_URL` | — | `jdbc:postgresql://host:5432/db` |
| `POSTGRES_USER` | — | |
| `POSTGRES_PASSWORD` | — | (или `POSTGRES_PASSWORD_FILE` для secrets) |
| `RABBITMQ_HOST` | — | |
| `RABBITMQ_PORT` | 5672 | |
| `RABBITMQ_USER` | — | |
| `RABBITMQ_PASSWORD` | — | |
| `RABBITMQ_VHOST` | `/` | |
| `DASHBOARD_PORT` | 8080 | |
| `DASHBOARD_AUTH_USER` | `admin` | |
| `DASHBOARD_AUTH_PASSWORD` | — | если не задан — `none()` + warning в логах |
| `NODE_ID` | auto (`infra-${hostname}`) | |
| `RUN_MIGRATIONS` | `true` | можно отключить если миграции через CI/k8s Job |
| `OUTBOX_POLL_INTERVAL_MS` | 100 | |
| `RECURRING_POLL_INTERVAL_MS` | 30000 | |
| `FAST_FORWARD_WINDOW_HOURS` | 24 | |
| `SAFETYNET_POLL_INTERVAL_MS` | 30000 | |
| `ARCHIVE_S3_BUCKET` | — | задать → включить S3-архивацию удаляемых rows (18.7); не задан → `Noop` |
| `ARCHIVE_S3_REGION` | `us-east-1` | `auto` для Cloudflare R2 |
| `ARCHIVE_S3_ENDPOINT` | — | custom endpoint для MinIO/R2/GCS-S3/Spaces; не задан → реальный AWS |
| `ARCHIVE_S3_ACCESS_KEY` / `ARCHIVE_S3_SECRET_KEY` | — | оба или ни одного (иначе AWS default credential chain); `ARCHIVE_S3_SECRET_KEY_FILE` для secrets |
| `ARCHIVE_S3_PATH_STYLE` | auto | `true`/`false`; по умолчанию path-style при заданном endpoint |
| `ARCHIVE_S3_KEY_PREFIX` | — | префикс ключа объектов |

`:archival-s3` (AWS SDK) забандлен в prebuilt scheduler-infra образ, чтобы S3-архивацию можно было включить только через env, без пересборки. Core-модули остаются SDK-free.

### 14.6. Docker Compose для local dev

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler
      POSTGRES_DB: scheduler
    ports: ["5432:5432"]
    volumes: [pg_data:/var/lib/postgresql/data]
    healthcheck: { test: ["CMD-SHELL", "pg_isready -U scheduler"] }

  rabbitmq:
    build: ./docker/rabbitmq    # с delayed-message plugin
    environment:
      RABBITMQ_DEFAULT_USER: scheduler
      RABBITMQ_DEFAULT_PASS: scheduler
    ports: ["5672:5672", "15672:15672"]
    volumes: [rabbit_data:/var/lib/rabbitmq]

  scheduler-infra:
    image: taskscheduler/infra:latest
    environment:
      POSTGRES_URL: jdbc:postgresql://postgres:5432/scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: scheduler
      RABBITMQ_PASSWORD: scheduler
      DASHBOARD_AUTH_PASSWORD: admin
    ports: ["8080:8080"]
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_started }
    restart: unless-stopped

  app:                          # demo user app (worker)
    build: ./app
    environment:
      POSTGRES_URL: jdbc:postgresql://postgres:5432/scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: scheduler
      RABBITMQ_PASSWORD: scheduler
    depends_on: [postgres, rabbitmq, scheduler-infra]

volumes: { pg_data: {}, rabbit_data: {} }
```

### 14.7. Docker image build (наш scheduler-infra)

`docker/infra/Dockerfile`:
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY standalone-runner-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Java 21 LTS — соответствует `jvmToolchain(21)` в `buildSrc/src/main/kotlin/buildsrc/convention/kotlin-jvm.gradle.kts`. LTS-версия, безопасный выбор для prod.

Gradle: `:standalone-runner` собирается через Shadow plugin в fat-jar со всеми deps (engine-infra, dashboard-server, dashboard-web статика как resources, transport-rabbit, storage-postgres).

CI публикует `taskscheduler/infra:${version}` в registry.

---

## 15. Kotlin/JS + React frontend setup

### 15.1. `:core:shared` как Kotlin Multiplatform

Главное архитектурное решение для шаринга типов между JVM-сервером и браузерным клиентом — `:core:shared` это KMP-модуль:

```kotlin
// core/shared/build.gradle.kts (через buildsrc.convention.kotlin-multiplatform)
kotlin {
    jvm()                          // для :core:backend, :storage-postgres, :engine-*, :dashboard-server
    js(IR) {                       // для :core:frontend, :dashboard-web
        browser()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.bundles.kotlinxEcosystem)
        }
    }
}
```

`@Serializable data class JobView(...)` живёт в `commonMain` -> автоматически доступно везде. **Type safety через всю границу сервер<->клиент** — главная мотивация писать фронт на Kotlin, а не на TypeScript.

JVM-модули используют стандартно: `implementation(project(":core:shared"))` — Gradle резолвит JVM target. js-модули — тот же синтаксис, Gradle резолвит js target.

**Почему `js(IR)`, а не `wasmJs`:** React-обёртки JetBrains (`kotlin-react`, `kotlin-emotion`) публикуют только `js`-варианты. Wasm для DOM-фреймворка и не нужен — узкое место дашборда это сеть и рендер DOM, а не вычисления.

### 15.2. `:dashboard-web` структура

```kotlin
// dashboard-web/build.gradle.kts
plugins {
    id("buildsrc.convention.kotlin-js-react")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    js(IR) {
        binaries.executable()
        browser {
            commonWebpackConfig {
                outputFileName = "dashboard-web.js"
            }
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":core:shared"))
            // :core:frontend реэкспортит React / Emotion / Ktor / Decompose как `api`
            implementation(project(":core:frontend"))
            implementation(project.dependencies.platform(libs.kotlinWrappersBom))
        }
    }
}
```

**Версии wrappers пинятся жёстко.** Каждый релиз `kotlin-wrappers` собран одной конкретной версией Kotlin, и его klib нечитаем более старым компилятором. `2026.6.1` — последний на Kotlin 2.3.21 (начиная с `2026.6.2` пошёл 2.4.x); на Kotlin 2.4.10 стоит `2026.8.0`. Поднимать эту версию можно только вместе с версией Kotlin.

Структура исходников (`presentation/root/...`, `presentation/screens/{name}/...`) — см. секцию 3.4. Слои `data/` и `domain/`, а также весь Component-слой Decompose, к UI-фреймворку не привязаны.

### 15.3. Технологические выборы

| Слой | Технология |
|---|---|
| UI | **React 19** через `kotlin-react` / `kotlin-react-dom` |
| Стилизация | **Emotion** (`kotlin-emotion-react`) — CSS-in-Kotlin, типизированный через `web.cssom` |
| Дизайн-система | Собственная (`:core:frontend/ui` + `theme`) — палитра "Graphite", IBM Plex Sans, набор контролов и табличных примитивов |
| Routing | **Decompose 3.x** (`com.arkivanov.decompose`) — sealed-class config + web history + child components |
| HTTP client | `ktor-client` с `js` engine + content-negotiation kotlinx-serialization |
| WebSocket | `ktor-client-websockets` |
| State | **Decompose `Value<T>`** внутри Component (роль ViewModel), в React читается хуком `useValue` |
| Theme | CSS custom properties + `data-theme` на `<html>`, **auto-detect + persistent override** |

**Почему Decompose остался при переходе на React:** его core UI-агностичен. Весь навигационный стек, стейт экранов, фильтры, сортировка и web-history переехали с wasm-сборки без единой правки — переписывался только слой рендера.

**Почему своя дизайн-система, а не готовая UI-библиотека:** вся поверхность дашборда — это кнопки, поля, чипы и таблицы. Владеть ими дешевле, чем тянуть зависимость с собственным циклом релизов, и это единственный способ повторить "Graphite" точь-в-точь.

### 15.4. Мост Decompose -> React

`useValue` — единственная точка связи между Decompose и React:

```kotlin
public fun <T : Any> useValue(value: Value<T>): T {
    val subscribe: (() -> Unit) -> Cleanup = useMemo(value) { /* value.subscribe(...) */ }
    return useSyncExternalStore(subscribe, { value.value })
}
```

Построен на `useSyncExternalStore` — штатном примитиве React для внешнего мутабельного стора: читает снапшот прямо во время рендера (первый кадр уже с актуальными данными) и корректен при конкурентном рендеринге.

### 15.5. Theme — auto + toggle с persistence

Обе палитры всегда присутствуют в стилях как CSS-переменные; какая победит — решает атрибут `data-theme` на `<html>`:

```kotlin
// SchedulerGlobalStyles монтируется один раз в корне
":root"               { paletteVariables(LightPalette) }
"[data-theme='dark']" { paletteVariables(DarkPalette) }

// переключение — одна запись атрибута, без ре-рендера React-дерева
public fun applyThemeMode(isDark: Boolean) {
    document.documentElement.setAttribute("data-theme", if (isDark) "dark" else "light")
}
```

Компоненты читают токены как `SchedulerColors.primary` -> `var(--sch-primary)`. Persistence в `localStorage` (`dashboard.dark`); дефолт при первом заходе — системная тема.

### 15.6. Build coupling: copy task

`:standalone-runner` подключает JS-бандл через копирование из `:dashboard-web` build output:

```kotlin
// standalone-runner/build.gradle.kts
tasks.processResources {
    from(project(":dashboard-web").layout.buildDirectory.dir("dist/js/productionExecutable")) {
        into("dashboard-web")
    }
    dependsOn(":dashboard-web:jsBrowserDistribution")
}
```

В runtime KTOR раздаёт:
```kotlin
routing {
    staticResources("/", "dashboard-web") {
        default("index.html")    // SPA fallback для роутов клиента
    }
    authenticate(...) {
        route("/api") { ... }
        webSocket("/api/events") { ... }
    }
}
```

Same origin -> нет CORS в prod. Bundle и API оба на порту 8080 (или другой что задан в `DASHBOARD_PORT`).

### 15.7. Dev workflow

**Производственная сборка** через `./gradlew :standalone-runner:run` — собирает JS, копирует в resources, поднимает KTOR. Полный цикл.

**Dev итерация** (быстрее):
1. Запускаем `scheduler-infra` (без dashboard) на 8081: `docker compose up -d postgres rabbitmq` + `./gradlew :standalone-runner:run -PdashboardEnabled=false` (или через env)
2. Запускаем dashboard-web в dev-режиме: `./gradlew :dashboard-web:jsBrowserDevelopmentRun --continuous` -> webpack-dev-server на 8080 с HMR
3. Webpack проксирует `/api/*` -> `localhost:8081`
4. Открываем `http://localhost:8080` -> правим Kotlin-код -> видим в браузере без полной пересборки

**Без бэкенда вообще:** `http://localhost:8080/?mock` подменяет REST-репозитории на in-memory сэмплы (`data/mock/MockRepositories.kt`) — все экраны рендерятся заполненными. Для работы над UI этого достаточно.

### 15.8. Bundle size

| Компонент | Размер |
|---|---|
| `dashboard-web.js` (minified) | ~1.7 МБ |
| IBM Plex Sans (4 начертания, ttf) | ~800 КБ |
| **Итого первая загрузка** | **~2.5 МБ** (~700 КБ gzipped) |

Для сравнения, предыдущая Compose/Wasm-сборка весила ~13 МБ на тех же экранах: Skiko рисовал UI на canvas и тянул собственный графический движок. Переход на DOM убрал эту статью расхода целиком.

**Оптимизации в prod build:**
- webpack production mode (минификация + tree shaking) — встроено в `jsBrowserDistribution`
- `install(Compression)` в KTOR — gzip/brotli на static
- HTTP cache headers (`Cache-Control: immutable`) для версионированных asset-ов
- Шрифты кэшируются браузером -> повторные визиты быстрые

### 15.9. Browser support

Обычный ES2015-бандл + DOM: работает во всех актуальных браузерах, включая те, где Wasm GC (требование прежней сборки) недоступен. Отдельного ограничения по версиям больше нет.

---

## 16. Retry policy and error handling

### 16.1. Дефолт

```kotlin
RetryPolicy.exponentialBackoff(
    maxAttempts = 3,
    initial = 1.seconds,
    max = 1.hours,
    multiplier = 2.0,
    jitter = Jitter.Full
)
```

Sequence без jitter: `1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 512s, 1024s, 2048s, ...` capped at 1h. С `maxAttempts=3` — 1 первая + 2 retry = 3 попытки.

**Full jitter** (Marc Brooker, AWS pattern): `actual_delay = random(0, exp_backoff_value)`. Защита от thundering herd когда N одинаковых jobs падают одновременно и потом одновременно ретраятся. Стандарт для distributed systems.

### 16.2. Exception classification — два механизма

**(a) Marker exception в handler-е:**
```kotlin
class SendEmailHandler : JobHandler<SendEmail> {
    override suspend fun execute(job: SendEmail) {
        val user = userRepo.find(job.userId)
            ?: throw NonRetriableJobException("User ${job.userId} not found")
        // ...
    }
}
```

`NonRetriableJobException` — публичный класс нашей либы. Бросок → job сразу `FAILED`, attempts не инкрементируется. Естественно для своего handler-кода.

**(b) Глобальный predicate для библиотечных exception:**
```kotlin
schedulerCoreModule {
    retryPolicy = exponentialBackoff(...) {
        notRetriableOn { e -> 
            e is IllegalArgumentException ||
            e is SerializationException ||
            (e is HttpClientException && e.status in 400..499)
        }
    }
}
```

Когда нет контроля над exception (бросает чужая либа).

### 16.3. Особые exception cases

| Exception | Что делаем |
|---|---|
| `NonRetriableJobException` | FAILED сразу, attempts не инкрементируется |
| `TimeoutCancellationException` (наш timeout) | RETRY стандартно |
| `CancellationException` (graceful shutdown / cancel из dashboard) | НЕ FAILED и НЕ RETRY — state как был, orphan recovery подберёт |
| Любое другое (если не попадает в `notRetriableOn`) | RETRY если attempts < max, иначе FAILED |

### 16.4. Per-handler override

```kotlin
@Single(binds = [JobHandler::class])
@JobType(HeavyReport::class)
class HeavyReportHandler(...) : JobHandler<HeavyReport> {
    
    override val retryPolicy = exponentialBackoff(
        maxAttempts = 5,
        initial = 1.minutes,
        max = 6.hours
    )
    
    override suspend fun execute(job: HeavyReport) { ... }
}
```

**Property override**, не аннотация. Аннотации могут содержать только константы — нельзя выразить `1.minutes` или custom jitter. Property — type-safe, гибче, идиоматично для Kotlin.

Если property не переопределена → global default из `schedulerCoreModule`.

### 16.5. Per-enqueue override

```kotlin
scheduler.enqueue(SendEmail(123, "urgent"), EnqueueOptions(
    maxAttempts = 10,
    retryPolicy = exponentialBackoff(initial = 100.milliseconds, max = 30.seconds)
))
```

**Приоритет:** enqueue > handler property > global default.

### 16.6. onFinalFailure hook

`JobHandler` определяет опциональный hook (полная сигнатура интерфейса — секция 12.4):

```kotlin
interface JobHandler<T : Job> {
    suspend fun execute(ctx: JobContext, job: T)
    val retryPolicy: RetryPolicy? get() = null
    
    /** 
     * Вызывается ПОСЛЕ финального FAILED (исчерпан maxAttempts или non-retriable).
     * ctx.attempt — финальный счётчик попыток. Дефолт пустой.
     */
    suspend fun onFinalFailure(ctx: JobContext, job: T, error: Throwable) {}
}
```

Пример:
```kotlin
class SendEmailHandler(
    private val mailer: Mailer,
    private val alerts: AlertingService,
) : JobHandler<SendEmail> {
    override suspend fun execute(ctx: JobContext, job: SendEmail) = mailer.send(...)
    
    override suspend fun onFinalFailure(ctx: JobContext, job: SendEmail, error: Throwable) {
        alerts.notify("Email permanently failed for user ${job.userId} after ${ctx.attempt} attempts: ${error.message}")
    }
}
```

`onBeforeRetry` — не делаем в MVP, observability покрывается `job_event` + dashboard + логами.

### 16.7. DB flow при retry

При исключении в handler (retriable, attempts < max):
```sql
UPDATE job 
SET state = 'AWAITING_RETRY',
    scheduled_at = now() + (backoff_ms || ' milliseconds')::interval,
    attempts = attempts + 1,
    locked_by = NULL, locked_until = NULL,
    version = version + 1
WHERE id = :id AND version = :v;

INSERT INTO job_event (job_id, event_type, error_msg, error_stack, occurred_at)
VALUES (:id, 'RETRY', :msg, :stack, now());

INSERT INTO outbox (job_id, routing_key, delay_ms)
VALUES (:id, :queue, :backoff_ms);
```

При non-retriable / max_attempts exhausted:
```sql
UPDATE job SET state = 'FAILED', ... WHERE id = :id;
INSERT INTO job_event (event_type = 'FAILED', error_msg, error_stack);
-- DAG: обработать dependents (см. 7.4) по on_failure rule
-- handler.onFinalFailure(...) вызывается ПОСЛЕ COMMIT в БД
```

### 16.8. Long backoff edge case

Если backoff после N-й попытки достиг >24ч (за пределами delayed exchange окна):
- `scheduled_at = now + 30h` → `state = SCHEDULED`, без outbox
- Fast-forward task в infra container (см. 11.5) подхватит когда войдёт в 24h окно → outbox → delayed exchange

Существующая инфраструктура справляется без изменений в retry-логике.

### 16.9. Backoff математика — реализация

```kotlin
class ExponentialBackoff(
    val maxAttempts: Int,
    val initial: Duration,
    val max: Duration,
    val multiplier: Double,
    val jitter: Jitter,
) : RetryPolicy {
    
    override fun nextBackoff(attempts: Int): Duration {
        val exp = initial * multiplier.pow(attempts - 1)
        val capped = minOf(exp, max)
        return when (jitter) {
            Jitter.None -> capped
            Jitter.Full -> Duration.fromMilliseconds(Random.nextLong(0, capped.inWholeMilliseconds))
            is Jitter.Equal -> capped * (1.0 - jitter.factor + Random.nextDouble() * jitter.factor * 2)
        }
    }
}
```

`Jitter.Equal(0.25)` = ±25% — алтернатива full jitter если кому-то нужен предсказуемый минимум.

---

## 17. Idempotency

### 17.1. Фундаментальная гарантия — at-least-once

Job может быть выполнен **больше одного раза** в этих сценариях:

| Сценарий | Когда |
|---|---|
| Worker crash после side-effect, до UPDATE SUCCEEDED | redelivery через orphan recovery |
| GC pause > 90s (lockDuration) | worker "просыпается", lock уже у другого нода → side-effect выполнен дважды |
| Network partition worker ↔ PG в момент commit | worker не уверен прошёл ли UPDATE → может попробовать ещё раз |
| Rabbit redelivery (ack потерян) | UPDATE WHERE state=ENQUEUED спасает в большинстве случаев, но тонкие race есть |
| Manual retry из dashboard | явное действие |

**Exactly-once невозможно** без distributed transactions через PG + Rabbit + внешние системы (HTTP API, email, payment). Поэтому **гарантируем at-least-once**, а идемпотентность handler-кода — ответственность пользователя. Наша работа — дать удобные инструменты.

### 17.2. JobContext как основа

Полная сигнатура — в секции 12.4. Ключевое поле: `ctx.jobId` — **стабильный идемпотентный ключ** для всех попыток одного job-а (retry, recovery, redelivery). Не меняется между attempts.

Базовый паттерн дедупликации:
```kotlin
class SendEmailHandler(
    private val mailer: Mailer,
    private val sentLog: SentEmailRepository,
) : JobHandler<SendEmail> {
    override suspend fun execute(ctx: JobContext, job: SendEmail) {
        if (sentLog.alreadySent(ctx.jobId)) {
            log.info("Job ${ctx.jobId} already sent — skipping")
            return
        }
        mailer.send(job.userId, job.template)
        sentLog.markSent(ctx.jobId)
    }
}
```

### 17.3. Встроенный IdempotencyStore (PG-backed default)

Pluggable интерфейс с PG-реализацией по дефолту:

```kotlin
interface IdempotencyStore {
    /** 
     * Атомарно отмечает (jobId, action) как обработанный.
     * Returns true если первый раз, false если уже обработан (duplicate execution).
     */
    suspend fun tryMark(jobId: UUID, action: String = "default"): Boolean
}
```

PG impl через `idempotency_log` таблицу (см. секцию 6):
```kotlin
class PostgresIdempotencyStore(private val ds: DataSource) : IdempotencyStore {
    override suspend fun tryMark(jobId: UUID, action: String): Boolean =
        execUpdate("""
            INSERT INTO idempotency_log (job_id, action, occurred_at)
            VALUES (?, ?, now())
            ON CONFLICT (job_id, action) DO NOTHING
        """, jobId, action) == 1
}
```

Атомарность через PRIMARY KEY (`job_id, action`) → race-free.

Использование single-step:
```kotlin
class SendEmailHandler(..., private val idem: IdempotencyStore) : JobHandler<SendEmail> {
    override suspend fun execute(ctx: JobContext, job: SendEmail) {
        if (!idem.tryMark(ctx.jobId)) return   // duplicate — skip
        mailer.send(...)
    }
}
```

Multi-step (для составных handler-ов):
```kotlin
override suspend fun execute(ctx: JobContext, job: ProcessOrder) {
    if (idem.tryMark(ctx.jobId, "charge"))   chargePayment(job)
    if (idem.tryMark(ctx.jobId, "notify"))   sendConfirmation(job)
    if (idem.tryMark(ctx.jobId, "fulfill"))  scheduleShipping(job)
}
// При retry — выполнятся только незавершённые шаги
```

Регистрация в DI:
```kotlin
schedulerCoreModule {
    idempotencyStore = postgresIdempotencyStore()   // default
    // или: idempotencyStore = null  // отключено, юзер handle-ит сам
}
```

Redis/Mongo-backed варианты — Phase 2.

### 17.4. enqueueOnce — дедупликация на уровне enqueue

Другая проблема: producer-side dedup. Хотим "если этот job уже создан — не создавать второй".

```kotlin
scheduler.enqueueOnce(
    key = "daily-report-2026-05-23",
    job = DailyReport(date = "2026-05-23")
)
// если job с таким key в state IN (active states) — возвращает existing UUID, не создаёт новый
// если такой key завершился (SUCCEEDED/FAILED/CANCELLED) — создаёт новый
```

Реализация — column `idempotency_key` в `job` (см. секцию 6) + unique partial index:
```sql
CREATE UNIQUE INDEX job_idempotency_key_active_idx ON job (idempotency_key)
  WHERE state IN ('AWAITING_DEPS', 'SCHEDULED', 'ENQUEUED', 'PROCESSING', 'AWAITING_RETRY')
    AND idempotency_key IS NOT NULL;
```

Атомарный INSERT с conflict-handling:
```sql
INSERT INTO job (..., idempotency_key) VALUES (..., :key)
ON CONFLICT (idempotency_key) WHERE state IN (...) 
  DO NOTHING
RETURNING id;
-- если 0 rows: SELECT id FROM job WHERE idempotency_key = :key AND state IN (...)
```

Кейсы использования:
- Cron триггер сработал дважды из-за clock skew → второй enqueue игнорируется
- User double-click → один job
- Recovery scenarios → повторный enqueue безопасен

**Отличие от IdempotencyStore:**
- `IdempotencyStore` — handler-side, защищает от double-EXECUTION одного job-а
- `enqueueOnce` — producer-side, защищает от double-CREATION jobs

Оба нужны, решают разные проблемы.

### 17.5. DAG idempotency — без дополнительной работы

При SUCCEEDED парента декрементируем `pending_deps` детей. Если парент "succeeded дважды":
```sql
UPDATE job SET state='SUCCEEDED' WHERE id=:id AND locked_by=:me AND version=:v
```
Второй раз — 0 rows updated (version изменился) → не продолжаем dependency processing. **Декремент ровно один раз.**

Existing optimistic locking уже защищает.

### 17.6. Рекомендации

Patterns которые документируем в README:

| Side-effect | Рекомендация |
|---|---|
| Запись в БД | UPSERT с `jobId` как natural key, или `ON CONFLICT DO NOTHING` |
| HTTP API call | `Idempotency-Key: ${ctx.jobId}` header (Stripe-style) |
| Email / SMS | Своя таблица `sent_log(job_id PK)` или встроенный `IdempotencyStore` |
| Файловая система | Atomic `mv tmp → final`, check `exists` перед записью |
| Multi-step | `idem.tryMark(jobId, "stepN")` для каждого шага |
| Чистая computation (без side-effect) | Ничего не нужно — повторное выполнение безопасно |

---

## 18. Audit & retention

### 18.1. Что растёт со временем

| Таблица | Рост | Стратегия |
|---|---|---|
| `job` (terminal: SUCCEEDED/FAILED/CANCELLED) | 1 row на enqueue | retention по state |
| `job_event` | 3-10+ rows на job | CASCADE с `job` |
| `job_dependency` | rows на DAG edges | CASCADE с `job` |
| `idempotency_log` | 1-N rows на job (multi-step) | **отдельный** TTL (см. 18.4) |
| `outbox` | 1 row на enqueue | TTL после `published_at` |
| `worker` | dead nodes остаются | TTL после `last_heartbeat` |

### 18.2. RetentionCleanup task в engine-infra

Новая background-задача в scheduler-infra рядом с outbox/recurring/fast-forward/safety-net. Запускается раз в `cleanupInterval`. Batch + throttle чтобы не зажать БД.

```kotlin
suspend fun runRetentionCleanup() {
    while (isActive) {
        delay(config.cleanupInterval)
        cleanupTerminalJobs("SUCCEEDED",  config.retention.succeeded)
        cleanupTerminalJobs("FAILED",     config.retention.failed)
        cleanupTerminalJobs("CANCELLED",  config.retention.cancelled)
        cleanupOutboxPublished(config.retention.outboxPublished)
        cleanupIdempotencyLog(config.retention.idempotencyLog)
        cleanupDeadWorkers(config.retention.deadWorkers)
    }
}
```

Каждая операция — batch DELETE с LIMIT, цикл пока возвращает rows или достигнут лимит итераций.

### 18.3. Конфигурация

```kotlin
schedulerInfraModule {
    retention {
        succeeded       = 7.days        // null = keep forever
        failed          = 30.days
        cancelled       = 7.days
        outboxPublished = 1.hours       // после published_at
        idempotencyLog  = 30.days
        deadWorkers     = 1.days        // после last_heartbeat
    }
    cleanupInterval  = 1.hours
    cleanupBatchSize = 10000             // LIMIT в DELETE
}
```

`null` для любого поля = "хранить вечно" (для compliance-требований).

### 18.4. Почему idempotency_log БЕЗ CASCADE

`idempotency_log` намеренно не имеет FK к `job`. Причина — TTL может быть **дольше** чем у job:

Пример: handler делает HTTP API call с `Idempotency-Key: ${ctx.jobId}`. Внешний API хранит ключ 30 дней. Если бы `idempotency_log` каскадно удалялся с job через 7 дней — мы бы потеряли защиту от двойного исполнения в случае если по какой-то причине job был re-enqueue-нут с тем же ID.

Отдельный TTL длиннее job-retention — безопаснее. Дефолт 30 дней совпадает с типичным TTL HTTP Idempotency-Key.

### 18.5. SQL cleanup

**Terminal jobs (с CASCADE на job_event, job_dependency, outbox):**
```sql
DELETE FROM job
WHERE id IN (
    SELECT id FROM job 
    WHERE state = :state AND updated_at < now() - :retention::interval
    LIMIT :batchSize
);
-- job_event, job_dependency, outbox удалятся CASCADE
```

**outbox (published):**
```sql
DELETE FROM outbox 
WHERE published_at IS NOT NULL 
  AND published_at < now() - :retention::interval;
```

**idempotency_log:**
```sql
DELETE FROM idempotency_log 
WHERE occurred_at < now() - :retention::interval;
```

**worker (dead):**
```sql
DELETE FROM worker 
WHERE last_heartbeat < now() - :retention::interval;
```

### 18.6. Audit для manual actions

`job_event.actor` column (см. секцию 6) заполняется при действиях из dashboard. Auth context определяет identity:

| Auth тип | Откуда actor |
|---|---|
| `basic { ... }` | `username` из basic auth header |
| `custom("my-auth")` | пользователь предоставляет `actorExtractor: (ApplicationCall) -> String` в DSL |
| `none()` | `"anonymous"` |

```kotlin
schedulerDashboardModule {
    auth {
        custom("oauth") {
            actorExtractor = { call -> 
                call.principal<UserIdPrincipal>()?.name ?: "unknown"
            }
        }
    }
}
```

Event types для manual actions:
- `MANUAL_RETRY` — POST /api/jobs/{id}/retry (см. 9.5, сбрасывает attempts)
- `MANUAL_CANCEL` — POST /api/jobs/{id}/cancel
- `MANUAL_DELETE` — DELETE /api/jobs/{id}
- `MANUAL_TRIGGER` — POST /api/recurring/{id}/trigger

JobDetail timeline показывает actor в строках manual events:
```
[12:30] ENQUEUED by system
[12:31] STARTED on worker-1
[12:35] FAILED — TimeoutException
[14:20] MANUAL_RETRY by admin@example.com
[14:21] STARTED on worker-2
[14:22] SUCCEEDED
```

### 18.7. Archival — shipped (Phase 3)

Архивация удаляемых rows в S3/cold storage перед DELETE — серьёзная фича для compliance-проектов (банк, healthcare). Текущая реализация:

1. **Pluggable `ArchivalSink` interface** (`core/backend/.../archival/ArchivalSink.kt`) — `suspend fun archive(category: String, batch: List<ArchivedJobRecord>)`. Categories — `"job.succeeded"`, `"job.failed"`, `"job.cancelled"`. `Noop` дефолт-биндинг (Koin), пользователь оверрайдит `single<ArchivalSink> { ... }` в своём модуле.
2. **Reference impl: `FileArchivalSink(baseDir: Path)`** — пишет JSONL под `baseDir/<category>/<YYYY-MM-DD>.jsonl`. Append-mode + UTC partitioning, не fsync — для dev/single-node ОК.
3. **Cloud impl: `S3ArchivalSink`** (отдельный opt-in модуль `:archival-s3`, AWS SDK v2) — пишет батч одним JSONL-объектом в `s3://<bucket>/[<prefix>/]<category>/<YYYY-MM-DD>/<content-hash>.jsonl`. Работает с AWS S3, MinIO, Cloudflare R2, GCS (S3 API), DigitalOcean Spaces через `endpoint` override + path-style. **Идемпотентность**: ключ полностью производный от содержимого (записи сортируются по `id`, день — из max `updatedAt` батча, имя файла — SHA-256 байтов), поэтому повтор того же батча при ретрае (sink бросил → DELETE пропущен → те же rows на следующем тике) перезаписывает объект, а не дублирует. Тяжёлый SDK изолирован в своём модуле — core остаётся SDK-free; подключение `single<ArchivalSink> { S3ArchivalSink.create(bucket=…, endpoint=…, region=…) }` в Koin-модуле ПОСЛЕ `schedulerInfraModule` (last-wins). Тест: MinIO Testcontainer (round-trip + идемпотентность + раздельные префиксы).
4. **Cleanup pipeline** (`RetentionCleanupBatchUseCase`): `SELECT terminal WHERE updated_at < cutoff` → `ArchivalSink.archive(...)` → `DELETE WHERE id IN (...) AND state = ?`. Если sink бросает — DELETE для этого batch ПРОПУСКАЕТСЯ (log.error), rows доживают до следующего тика, sink получает второй шанс. Другие buckets в этом же тике не страдают.
5. **Архивируется только `job` row.** `job_event`, `job_dependency`, `outbox` каскадно удаляются с parent — если пользователю нужен полный audit lineage, он подключает свой собственный sink и читает их сам перед DELETE.

`ArchivedJobRecord` (в `core:shared`) — wire-stable DTO с примитивными типами (без `JobPriority` value wrapper), чтобы JSON был портативным для не-Kotlin читателей cold storage.

### 18.8. Дефолты — реалистично для среднего workload

10-100k jobs/day:
- `succeeded = 7.days` × 70k = ~490k SUCCEEDED rows одновременно — норм для PG
- `failed = 30.days` × 7k (1%) = ~210k FAILED rows
- `idempotency_log = 30.days` × multi-step = до ~10M rows worst case — норм с PK index

Для 1M+ jobs/day пользователь сокращает: `succeeded = 1.days`, `failed = 7.days`. Конфигурируется.

---

## 19. Priority API

### 19.1. Два уровня приоритезации

| Уровень | Механизм |
|---|---|
| **Между queue-ами** | Архитектурно: разные queue → разные worker pools. `queue("urgent", concurrency=10)` отдельно от `queue("background", concurrency=2)`. Никакого priority API. |
| **Внутри одной queue** | Rabbit native priority queues — `x-max-priority: 10` (уже в topology, см. 11.3), `BasicProperties.priority` при publish |

### 19.2. Range и семантика

- **Range:** `0..10` (Rabbit стандарт, max recommended)
- **Default:** `0`
- Higher priority → доставляется consumer-у раньше
- **Best-effort, не строгий гарант** — `prefetchCount` буфер на consumer-е может перемешать. С `prefetchCount=10` и нормальным потоком — приемлемо

### 19.3. Override chain

Финальный priority job-а вычисляется как первое не-null:

1. **per-enqueue** — `EnqueueOptions(priority = N)`
2. **per-handler** — `override val defaultPriority = N` (см. 12.4)
3. **per-queue** — `queue("urgent", defaultPriority = N)` в `schedulerWorkerModule`
4. **global default** = 0

### 19.4. API примеры

```kotlin
// (1) per-enqueue — самый высокий приоритет
scheduler.enqueue(SendEmail(...), EnqueueOptions(priority = 9))

// (2) per-handler default
@Single(binds = [JobHandler::class])
@JobType(WelcomeEmail::class)
class WelcomeEmailHandler(...) : JobHandler<WelcomeEmail> {
    override val defaultPriority = 5        // welcome всегда повыше обычного
    override suspend fun execute(ctx: JobContext, job: WelcomeEmail) { ... }
}

// (3) per-queue default
schedulerWorkerModule {
    queue("urgent", concurrency = 10, defaultPriority = 8)
    queue("default", concurrency = 10)       // priority = 0
}

// (4) recurring с priority
scheduler.recurring("hourly-monitor", "0 * * * *", MonitorJob(), priority = 7)
```

### 19.5. Маппинг на Rabbit при publish

Publisher (в infra container) читает `priority` из `outbox`:
```kotlin
channel.basicPublish(
    "jobs.dispatch", routingKey,
    AMQP.BasicProperties.Builder()
        .deliveryMode(2)
        .priority(outboxRow.priority)        // ← 0..10
        .headers(if (outboxRow.delay_ms > 0) mapOf("x-delay" to outboxRow.delay_ms) else null)
        .build(),
    jobId.toString().toByteArray()
)
```

`priority` column есть в `job`, `outbox`, `recurring_job` (см. секцию 6).

### 19.6. Priority + delayed exchange

`rabbitmq-delayed-message-exchange` plugin **сохраняет priority** при доставке. То есть scheduled / retry job с priority=9 после `x-delay` доставляется в target queue с priority=9.

Пока сообщение лежит в delayed exchange (ожидает delay) — priority в нём не играет роли (оно доставляется по времени, не конкурирует). Это нормально, priority важен только когда сообщения конкурируют за консьюмера.

### 19.7. DAG inheritance — НЕ наследуем

Child job по дефолту получает `priority = 0`, **не наследует** от parent-ов. Простая семантика, нет неожиданностей с `after(multipleParents)` где у parents разные priorities.

Для chain — convenience helper:
```kotlin
scheduler.chain(LoadCache(), ProcessData(), Notify(), priority = 9)
// все три шага получат priority = 9
```

Без helper-а — явный override в каждом enqueue.

**Shipped:** `EnqueueOptions(inheritPriorityFromParents = true)` на `enqueueAfter` — child получает `max(parent.priority)` (explicit `priority` в опциях выигрывает по override-chain; на не-DAG entry points — no-op, наследовать не от кого; удалённый retention-ом родитель считается за 0). Покрыто `DagIntegrationTest` (max-of-parents / explicit-wins / flag-off). Реализация — в `DefaultScheduler.enqueueAfter`, семантика — в KDoc `EnqueueOptions.inheritPriorityFromParents`.

### 19.8. Реализация в worker pickup

Rabbit сам сортирует доставку по priority — наш код не делает специальной логики. Просто `basicConsume`, сообщения приходят в порядке priority.

При manual retry из dashboard — priority сохраняется (берётся из `job.priority`).

---

## 20. Backpressure and overload handling

### 20.1. Естественный backpressure — уже работает

```
Producer (enqueue)       Postgres         RabbitMQ                Worker
   │                        │                │                       │
   │── INSERT job/outbox ─▶ │                │                       │
   │   COMMIT, OK           │                │                       │
   │             outbox publisher (infra)    │                       │
   │                        │── SELECT ─────▶│                       │
   │                        │   publish      │                       │
   │                        │                │── deliver (prefetch) ─▶
   │                        │                │   [worker занят]      │
   │                        │                │← НЕТ ack, НЕТ доставки│
   │             queue в Rabbit РАСТЁТ        │
   │             (producer и БД не страдают)  │
```

Когда worker занят все слоты (`concurrency`) → не делает ack → Rabbit не доставляет → копится в queue. Producer и БД продолжают работать как обычно. Это правильное поведение для job-шедулера.

### 20.2. Lazy queues mode для high-volume

В topology declare добавляем `x-queue-mode: lazy` опционально:

```kotlin
schedulerRabbitModule {
    queues {
        queue("default")                                    // regular
        queue("heavy", mode = QueueMode.Lazy)               // lazy: persist to disk immediately
    }
}
```

`x-queue-mode: lazy` (Rabbit feature) — queue хранит сообщения на диске вместо памяти. Trade-off:
- ✅ Может расти до миллионов сообщений без OOM на broker
- ❌ Read latency чуть выше (disk I/O)

Подходит для очередей которые могут вырасти (batch processing, eventual-consistency workloads). Для урgent/email — regular mode лучше.

Default — `regular`. Lazy — opt-in per-queue.

### 20.3. Alert hook на queue depth

В `engine-infra` добавляется periodic checker:

```kotlin
schedulerInfraModule {
    alerts {
        queueDepthThreshold = mapOf(
            "default" to 10_000,
            "heavy"   to 100,
            "email"   to 5_000,
        )
        onQueueDepthAlert = { queue, depth, threshold ->
            // user callback — Slack, PagerDuty, log, custom
            alertingService.warn("Queue $queue: $depth (threshold $threshold)")
        }
    }
    alertCheckInterval = 1.minutes
}
```

Раз в `alertCheckInterval` (дефолт 1 мин):
```sql
SELECT queue, count(*) AS depth 
FROM job 
WHERE state = 'ENQUEUED' 
GROUP BY queue;
```
Для каждой queue с `depth > threshold` → callback. Idempotent (вызывается пока depth выше threshold) — пользователь сам делает debouncing если нужно.

### 20.4. Queue depth в `/api/stats/overview`

Уже в плане (см. 9.1). Dashboard показывает per-queue depth в реальном времени. Оператор видит "queue X растёт" → действия:
- Скейлить workers (больше нод)
- Увеличить concurrency
- Остановить producer
- Запустить manual cleanup

### 20.5. Что НЕ делаем в MVP

**Auto-reject новых jobs (`enqueueOrFail`)** — user-side concern:
- Только пользователь знает что делать с rejected job (drop, retry, queue elsewhere, fallback)
- Race condition между depth check и enqueue
- Документируем pattern в README:
  ```kotlin
  if (scheduler.queueDepth("email") > 10_000) {
      throw QueueTooBusyException()  // его, не наш
  }
  scheduler.enqueue(SendEmail(...))
  ```

**Auto-scaling** — внешняя infra-задача (k8s HPA на основе queue depth метрик), не наша.

**Circuit breaker per queue** — shipped (см. 20.8).

### 20.6. Реалистичные стратегии для пользователя

Документируем в README:

| Кейс | Решение |
|---|---|
| Predictable burst (90% работы ночью) | Static scaling — больше workers в нужное время |
| Random spike | k8s HPA на queue depth + Prometheus (`/metrics` — shipped, см. 20.9) |
| Downstream slow (HTTP API задыхается) | Decrease `concurrency` queue → меньше параллельных вызовов |
| Producer слишком быстрый | Producer-side rate limiter (semaphore / token bucket) |
| Permanent overload | Архитектура — больше железа или меньше работы |

### 20.7. Adaptive prefetch — shipped (Phase 3)

Per-queue `AdaptivePrefetch` config + `PrefetchTuner` (engine-worker). Each adaptive queue runs a background tuner loop on `tuneInterval` cadence:

- **Overload signal** (p95 latency > target × 1.5) → halve `channel.basicQos(prefetch)` (AIMD-style multiplicative decrease)
- **Idle signal** (p95 < target × 0.5) → additive bump `current + max(1, current/4)`
- **Dead band** [0.5×target … 1.5×target] → no change

Bounded by `minPrefetch / maxPrefetch`. Samples flow in from every `metrics.recordExecution` site (success, failure, timeout, cancel — slow failures tie up an in-flight slot just like slow successes). Rolling window (`ArrayDeque`, default 100) — older samples drop off the head; tuner refuses to act until window is ≥ 25% full to avoid noise on cold start. `channel.basicQos(N)` is broker-side live-update — runs on the existing consumer channel without restart. `ConsumerHandle.setPrefetch(N)` default-impl is a no-op so non-Rabbit transports (KafkaJobTransport when it lands, in-memory fakes) ignore the tuner safely.

```kotlin
schedulerWorkerModule {
    queue("variable",
        concurrency = 4,
        prefetch = 8,
        adaptive = AdaptivePrefetch(
            targetLatency = 2.seconds,
            minPrefetch = 4,
            maxPrefetch = 32,
        ),
    )
}
```

### 20.8. Circuit breaker per queue — shipped (Phase 3)

Per-queue `CircuitBreakerConfig` + `CircuitBreakerRegistry` (engine-worker). Three-state machine:

- **CLOSED** — normal. Outcomes flow into a rolling sample window. Trip to OPEN when `failures / total > errorRateThreshold` AND `samples >= minSamples`.
- **OPEN** — `WorkerPool.processLockedInner` checks `circuitBreakers.tryAcquire(queue)` after pickup; refused jobs are released through the same `DeferPausedJobUseCase` path (clear lock, re-publish outbox with `delayMs = openDuration`). After `openDuration` elapses, transitions to HALF_OPEN.
- **HALF_OPEN** — exactly one in-flight probe allowed; success → CLOSED (and window cleared), failure → OPEN for another cycle.

Per-node in-memory state (no cross-node sync). Other nodes keep serving when one trips — the breaker is consumer-side protection for one worker pool's downstream. Operator pause across the cluster is `job_type_pause` (DESIGN.md 22.1), not the breaker. CANCELLED outcomes don't feed the breaker (operator action ≠ downstream health signal); SUCCESS / FAILED / RETRIED do.

**The probe slot is the failure mode to design against.** While it's held every pickup on the queue is refused, so a slot that never comes back leaves nothing to close the breaker and nothing to re-open it — the queue is dead until the process restarts, while looking healthy from the outside (worker heartbeats fine, Rabbit shows `consumers=1, messages=0` because deliveries are released straight back with `delayMs = openDuration`). Two guards:

- `WorkerPool.processLockedInner` returns the slot in a `finally` around dispatch. Outcome paths return it by recording a sample; the paths with no health signal — CANCELLED, no registered handler, undecodable payload, cancel-on-pickup — go through `CircuitBreakerRegistry.releaseProbe(queue)`, which banks no sample and leaves the breaker in HALF_OPEN for the next candidate.
- `probeTimeout` (default 15 min) expires a probe that never reported at all. That happens when the `finally` itself can't run: a non-cooperative handler outlives `cancelGracePeriod`, the cancel listener force-FAILs the row, and the handler coroutine keeps running (`onCancelSignal`'s documented leak). Keep `probeTimeout` above the per-job timeout of the queue's jobs — below it, the only cost is an occasional second probe running next to a legitimately slow one.

Observed in production before the guards existed: a force-cancelled probe on the `marketplace` queue wedged HALF_OPEN for three hours; ~400 recurring jobs per hour were cancelled by REPLACE overlap while their deliveries bounced on the 5-minute re-queue.

```kotlin
schedulerWorkerModule {
    queue("flaky-api",
        concurrency = 4,
        circuitBreaker = CircuitBreakerConfig(
            errorRateThreshold = 0.5,
            minSamples = 10,
            sampleWindow = 1.minutes,
            openDuration = 30.seconds,
            probeTimeout = 15.minutes,   // > this queue's per-job timeout
        ),
    )
}
```

### 20.9. Prometheus `/metrics` endpoint — shipped (Phase 3)

Implemented surface (mounted via `MicrometerMetrics` plugin in `Application.kt`,
scraped by Prometheus at `/metrics`):

| Metric | Type | Tags | Source |
|---|---|---|---|
| `scheduler_job_execution_seconds` | Histogram | `queue, payload_type, outcome` | `MicrometerJobMetrics` |
| `scheduler_retry_total` | Counter | `queue, payload_type` | `MicrometerJobMetrics.recordRetry` (engine-worker) |
| `scheduler_jobs_by_state` | Gauge | `state` | `SchedulerMetricsBinder` (15s poll) |
| `scheduler_outbox_unpublished` | Gauge | — | `SchedulerMetricsBinder` |
| `scheduler_outbox_lag_seconds` | Gauge | — | `SchedulerMetricsBinder` |
| `scheduler_workers_alive` | Gauge | — | `SchedulerMetricsBinder` |
| `scheduler_workers_total` | Gauge | — | `SchedulerMetricsBinder` |
| `scheduler_worker_in_flight` | Gauge | `queue, node` | `WorkerMetricsBinder` |
| `scheduler_circuit_breaker_state` | Gauge | `queue, node` | `WorkerMetricsBinder` — emits only for queues with CB config |
| `scheduler_idempotency_dedup_total` | Counter | `action` | `MicrometerIdempotencyMetrics` (standalone-runner) |

Ready-made Grafana dashboard in `docs/grafana-dashboard.json` — import via Grafana UI → Dashboards → Import → Paste JSON. Provides panels for throughput by outcome, success rate, p50/p95/p99 latency, queue depth, retry rate, idempotency dedup rate, CB state heatmap, outbox lag, in-flight per node/queue.

User-app wiring (worker-side metrics): override the default Noop bindings with Micrometer-backed impls in your Koin module:

```kotlin
module {
    single<MeterRegistry> { PrometheusMeterRegistry(PrometheusConfig.DEFAULT) }
    single<JobMetrics> { MicrometerJobMetrics(get()) }
    single { WorkerMetricsBinder(get(), get(), get(), get()).also { it.bind() } }
}
koin.get<WorkerMetricsBinder>()  // force eager binding so gauges register at startup
```

### 20.10. Phase 3+ остатки

Не осталось — **backpressure visual indicator shipped**: `QueueHealthBadge` рисует ELEVATED/OVERLOADED pill на JobList по queue depth (см. 20.4).

---

## 21. Function reference API

### 21.1. Цель

Sealed-class handlers — главный путь регистрации jobs в MVP (см. секцию 12). Function-ref API — **сахар для простых случаев**, чтобы не плодить data class на каждый chord:

```kotlin
// вместо:
@Serializable data class SendEmail(val userId: Long, val template: String) : Job
@Single(binds = [JobHandler::class]) @JobType(SendEmail::class)
class SendEmailHandler(...) : JobHandler<SendEmail> { ... }
scheduler.enqueue(SendEmail(123, "welcome"))

// можно:
scheduler.enqueue(Mailer::send, 123L, "welcome")
```

**Function-ref в MVP, но вторым классом.** Sealed-class приоритетнее для long-lived или recurring jobs (см. 21.7 — refactor caveat).

### 21.2. API: typed overloads до 5 аргументов

```kotlin
fun <T : Any>             enqueue(m: KFunction1<T, *>, opts: EnqueueOptions = EnqueueOptions()): UUID
fun <T : Any, A1>         enqueue(m: KFunction2<T, A1, *>, a1: A1, opts: ... ): UUID
fun <T : Any, A1, A2>     enqueue(m: KFunction3<T, A1, A2, *>, a1: A1, a2: A2, opts: ... ): UUID
fun <T : Any, A1, A2, A3> enqueue(m: KFunction4<T, A1, A2, A3, *>, a1: A1, a2: A2, a3: A3, opts: ... ): UUID
fun <T : Any, A1, A2, A3, A4>     enqueue(m: KFunction5<...>, ... ): UUID
fun <T : Any, A1, A2, A3, A4, A5> enqueue(m: KFunction6<...>, ... ): UUID
```

Boilerplate в нашей либе один раз, пользователь видит typesafe API. Для >5 параметров — `data class` (sealed-class API всё равно удобнее).

### 21.3. Payload format

Function-ref jobs хранятся в `job.payload_json` как сериализованный `FunctionRefPayload`
(`core/shared/.../functionref/FunctionRefPayload.kt`):
```json
{
    "targetType": "com.example.Mailer",
    "methodSignature": "send(kotlin.Long,kotlin.String)",
    "args": [123, "welcome"]
}
```

Три вещи, в которых легко ошибиться, если конструировать такой payload руками:

- **Имена полей — camelCase** (это имена свойств `FunctionRefPayload`, сериализованные
  kotlinx как есть).
- **Поля-дискриминатора в теле нет.** Признак function-ref job — колонка
  `job.payload_type = "function_ref"` (константа `FunctionRefPayload.FUNCTION_REF_PAYLOAD_TYPE`),
  она же отличает их от sealed-class jobs, где `payload_type` = FQN класса.
- **`targetQualifier` при `null` в JSON отсутствует**, а не пишется как `null` — глобальный
  `Json` собран с `encodeDefaults = false` (см. 22.9).

`methodSignature` — `"имя(fqn,fqn,…)"`: без пробелов и без generic-аргументов, типы —
`qualifiedName` каждого параметра (`FunctionRefEnqueuer.methodSignatureOf`). Именно эта
строка сопоставляется с методом на воркере, поэтому перегрузки различаются по типам.

### 21.4. Резолюция target в Koin (на worker-е)

```kotlin
// при выполнении на worker-е — см. engine-worker/.../FunctionRefRunner.kt:
val payload = json.decodeFromString(FunctionRefPayload.serializer(), job.payload_json)
val targetClass = Class.forName(payload.targetType).kotlin

val target = if (payload.targetQualifier != null) {
    koin.get(targetClass, qualifier = named(payload.targetQualifier))
} else {
    koin.get(targetClass)  // throws NoBeanDefFoundException если не найден
}

val method = targetClass.functions.first {
    FunctionRefEnqueuer.methodSignatureOf(it) == payload.methodSignature
}
val args = decodeArgs(payload.args, method.parameters.drop(1))

method.callSuspend(target, *args)
```

Реализация оборачивает ошибку каждого шага в отдельное сообщение (класс не на classpath /
нет Koin-binding / сигнатура не совпала / не декодировался аргумент N), чтобы строка
`job_event` давала достаточно для диагностики. Исключения идут в обычный
retry / FAILED-механизм — отдельного состояния «function-ref handler not found» нет.

### 21.5. Multiple bindings: fail-fast + qualifier/subclass

При enqueue делаем check:
```kotlin
val count = koin.getAll<Mailer>().size
if (count > 1 && opts.targetQualifier == null) {
    throw IllegalArgumentException(
        "Multiple bindings of Mailer in Koin. Specify targetQualifier or use subclass reference."
    )
}
```

Три способа решить:

```kotlin
// (1) Один binding — работает out of the box
scheduler.enqueue(Mailer::send, 123L)

// (2) Несколько binding — qualifier override
scheduler.enqueue(Mailer::send, 123L, EnqueueOptions(targetQualifier = "smtp"))

// (3) Subclass reference — точно один экземпляр конкретного класса
scheduler.enqueue(SmtpMailer::send, 123L)
```

### 21.6. Args serialization — требования

Args сериализуются через kotlinx-serialization. Out of the box:
- Примитивы: `String, Long, Int, Boolean, Double, ...`
- Стандартные: `List<T>`, `Map<K, V>`, `Set<T>`, `Pair<A, B>`, `kotlinx.datetime.Instant`, ...
- Пользовательские `@Serializable` data classes

**Fail-fast при enqueue** если arg не сериализуется (а не на worker-е). Понятная ошибка типа `IllegalArgumentException("Argument 1 of type Foo is not @Serializable")`.

```kotlin
// ок
scheduler.enqueue(Mailer::send, 123L, "welcome")               // примитивы
scheduler.enqueue(Reporter::send, ReportSpec(...))             // @Serializable data class
scheduler.enqueue(Sync::sync, listOf("a", "b"))                // List<String>

// fail-fast
scheduler.enqueue(Mailer::send, MyNonSerializableClass(...))   // не @Serializable
```

### 21.7. Refactor caveat — известный trade-off

Function-ref jobs хрупкие к refactor-у:
- Переименовали `Mailer.send` → `Mailer.sendEmail` → старые в-очереди jobs не найдут метод → FAILED при выполнении
- Изменили signature (добавили обязательный параметр) → старые jobs не парсятся

**Это известная trade-off** function-ref vs sealed-class. Sealed-class data class более устойчив к refactor (компилятор подсветит).

Документируем в README:
- ✅ Function-ref ок для: ad-hoc jobs, тесты, скрипты, прототипирование, короткоживущие jobs
- ❌ Function-ref избегать для: recurring jobs, scheduled на дни вперёд, jobs которые могут лежать в queue долго

Для long-lived → sealed-class всегда.

### 21.8. Method overloading

Disambig через полную signature в payload:

```kotlin
class Mailer {
    suspend fun send(userId: Long): Unit
    suspend fun send(userId: Long, template: String): Unit
}
// Kotlin компилятор выбирает overload по типам в KFunction reference
// в payload сохраняем "send(kotlin.Long,kotlin.String)"
// при resolve находим method по signature, не по имени
```

### 21.9. Lambda capture через K2 compiler plugin — shipped

Полный lambda capture работает через **K2 kotlinc compiler plugin** (не KSP — KSP не умеет переписывать call-site, только генерировать новый код):

```kotlin
scheduler.enqueueLambda { mailer.send(123L, "welcome") }
```

**Реализация (Stage 2).** `:scheduler-compiler-plugin` регистрирует `IrGenerationExtension`
(`SchedulerIrGenerationExtension`), который на IR-стадии находит вызовы
`Scheduler.enqueueLambda { recv.method(args) }` и переписывает их в:

```kotlin
scheduler.enqueueFunctionRefRaw(
    "com.example.Mailer",                 // FQN типа получателя
    "send(kotlin.Long,kotlin.String)",    // формат FunctionRefEnqueuer.methodSignatureOf
    listOf(123L, "welcome"),
    options,                              // проброшен; опущен → дефолт на call-site
)
```

Почему строки + `listOf`, а не синтез `KFunction`-ссылки в IR: построение
reflection-capable `IrFunctionReference` многословно и хрупко между версиями компилятора
(rich-vs-plain reference split в 2.2). Две `String`-константы плюс `listOf(...)` тривиальны
и стабильны, а `Scheduler.enqueueFunctionRefRaw` рефлексивно восстанавливает `KFunction` из
типа+сигнатуры (через `FunctionRefEnqueuer.buildFromTarget`) и заходит в **тот же** payload-build +
insert, что и явный `enqueue(Recv::method, …)`. Полученная job-строка байт-в-байт идентична.

**Поддерживаемая форма лямбды** (всё остальное — compile ERROR из IR-стадии): одно выражение
`{ receiver.method(args...) }`, receiver — member-вызов (не top-level/extension/context),
не-generic метод с конкретными типами параметров, ≤5 аргументов.

**Резолюция fake-override.** Конкретный реализатор `Scheduler` несёт fake-override дефолтного
`enqueueLambda`; матчинг идёт через `resolveFakeOverride()`, поэтому call ловится независимо от
статического типа получателя.

**Подключение в user-app.** Gradle-subplugin `:scheduler-compiler-plugin-gradle`
(`id("cs.trade.scheduler.compiler")`); внутри этого репо — через
`kotlinCompilerPluginClasspath<SourceSet>(project(":scheduler-compiler-plugin"))`
(плагин само-регистрируется через `META-INF/services`). Тест:
`app/.../SchedulerLambdaCaptureTest` — гоняет реальный compile-time rewrite и сверяет payload
с явным reference-формом.

Без плагина `enqueueLambda { … }` бросает `IllegalStateException` в рантайме (stub в
`Scheduler`), подсказывая применить плагин или явный `enqueue(Recv::method, …)`.

В MVP по-прежнему доступны sealed-class + function-ref как compiler-free пути.

---

## 22. Operational features (pause / node-pinning / progress / metrics)

### 22.1. Pause/disable job types из dashboard — shipped

"Feature flag" для типов: временно остановить обработку всех jobs определённого типа.

**Use cases:** email-сервис лёг → пауза `SendEmail`; hotfix downstream API → стоп `SyncData`; ревью подозрительной активности → стоп `ProcessPayment`.

**Реализация:**
- Таблица `job_type_pause` (см. секцию 6)
- **Outbox publisher** не публикует jobs где `payload_type IN (paused)`. Накапливаются в outbox с `published_at IS NULL`. Фильтр живёт **в SQL** (`OutboxRepository.findUnpublished` — анти-джойн на `job_type_pause`), а не в коде паблишера: paused-строки лежат в голове id-ordered скана, и как только их накапливается больше batch-size, нефильтрованное `LIMIT`-окно состоит только из них — published=0, все остальные типы тихо голодают (прод-инцидент 2026-07-01: пауза 2 типов остановила весь шедулер за 4 часа). `countUnpublished` / `findOldestUnpublishedCreatedAt` используют тот же фильтр — backlog-WARN и `scheduler_outbox_lag_seconds` меряют *publishable* бэклог, припаркованные паузой строки не держат алерт в вечном breach
- **Worker** перед execute делает second-line check (race с pause-после-published):
  ```kotlin
  if (storage.isPaused(payload_type)) {
      storage.republishWithDelay(jobId, 1.minutes)   // через delayed exchange
      return
  }
  ```

**Unpause = catch-up:** при unpause никаких специальных действий — outbox publisher на следующей итерации видит, что type больше не paused, и публикует всё накопленное. Может вызвать "лавину" если пауза была долгой → пользователь сам ответственен за этот трейд-офф (предупреждение в UI при unpause если queue depth большой).

**API:**
```
GET    /api/types                           → [{ type, queue, paused, paused_by, paused_since, reason, depth, ... }]
POST   /api/types/{type}/pause              → body: { reason: "Email service down" }
POST   /api/types/{type}/unpause            → 202; warning если depth > 1000
```

**UI:** новая страница `/types` со списком всех `payload_type` (включая function-ref descriptors). Pause/unpause кнопки + modal для reason.

### 22.2. Node-pinning и tag-based routing — shipped

Use cases: GPU-нода для ML, sticky processing (нода держит кэш), debug ("перезапустить fail на конкретной ноде").

**Worker config:**
```kotlin
schedulerWorkerModule {
    nodeId = "gpu-worker-1"
    nodeTags = listOf("gpu", "us-east")
    
    queue("default", concurrency = 10)
}
```

**Topology (auto-declare в Rabbit):**
- `q.default`, `q.email`, ... — стандартные (из `worker.queues`)
- `q.node.${nodeId}` — для каждой worker-ноды свой
- `q.tag.${tag}` — для каждого tag (множественные workers с одним tag — все consume той же queue, balanced)

Все эти queues bind-ятся в существующий exchange `jobs.dispatch` с соответствующими routing keys: `node.${nodeId}`, `tag.${tag}`.

**Outbox publisher routing:**
```kotlin
val routingKey = when {
    job.target_node != null -> "node.${job.target_node}"
    job.target_tag  != null -> "tag.${job.target_tag}"
    else                    -> job.queue                 // default
}
```

**API:**
```kotlin
scheduler.enqueue(TrainModel(...), EnqueueOptions(targetNode = "gpu-worker-1"))
scheduler.enqueue(TrainModel(...), EnqueueOptions(targetTag  = "gpu"))
scheduler.recurring("nightly-gpu", "0 3 * * *", BatchTrain(), targetTag = "gpu")
```

**UI:**
- JobDetail: action "Re-route" → dropdown из alive workers / tags → `POST /api/jobs/{id}/reroute?nodeId=...`
- WorkerList: показывает `nodeId` + `nodeTags` каждой ноды

**Edge case:** target_node/target_tag не существует среди alive workers → job висит в queue вечно. **Alert** в WorkerList: "5 jobs waiting for offline node 'gpu-worker-1'" с кнопкой re-route на доступную ноду.

### 22.3. Progress reporting — shipped

Handler через `JobContext.updateProgress(progress, msg)` обновляет состояние, UI рисует прогресс-бар.

**API в handler:**
```kotlin
class HeavyReportHandler : JobHandler<HeavyReport> {
    override suspend fun execute(ctx: JobContext, job: HeavyReport) {
        ctx.updateProgress(0.1f, "Loading data...")
        loadData()
        ctx.updateProgress(0.5f, "Processing rows")
        processRows()
        ctx.updateProgress(0.9f, "Writing report")
        saveReport()
        // 1.0 ставится автоматически при SUCCEEDED
    }
}
```

**Throttling — критично:**
```kotlin
class JobContextImpl(...) : JobContext {
    private var lastProgressFlush = 0L
    override suspend fun updateProgress(progress: Float, msg: String?) {
        val now = System.currentTimeMillis()
        if (now - lastProgressFlush < 1000) return    // не чаще раз в секунду
        storage.updateProgress(jobId, progress, msg, Instant.now())
        lastProgressFlush = now
        // → PG NOTIFY → WS firehose event {t: "job_progress", id, progress, msg}
    }
}
```

Schema columns добавлены в `job` (см. секцию 6): `progress REAL`, `progress_msg TEXT`, `progress_updated_at TIMESTAMPTZ`.

**Счётчиковый прогресс-бар (JobRunr-style) — shipped.** Поверх `updateProgress` есть
`JobContext.progressBar(total): ProgressBar` для кейса «обработать N элементов»:
```kotlin
val bar = ctx.progressBar(total = items.size.toLong())
for (item in items) {
    try { process(item); bar.succeeded() }   // +1 успешный
    catch (e: Exception) { bar.failed() }     // +1 неуспешный
}
```
- `progress` остаётся единственным источником доли = `(succeeded + failed) / total`; счётчики
  `succeeded/failed/total` — дополнительная nullable-метаданность (миграция V6:
  `progress_succeeded/failed/total BIGINT`). Для plain `updateProgress` они `null`.
- Троттл общий с `updateProgress` (1/сек), но завершающий инкремент (`processed >= total`)
  пишется в обход троттла (`force`), иначе бар застрял бы у 100%. Счётчики на `AtomicLong` —
  потокобезопасно. Событие `job_progress` несёт `succeeded/failed/total` (nullable, wire-совместимо).

**UI:**
- JobList: progress bar в строке (если есть)
- JobDetail: большой progress bar + текущий `progress_msg`. При наличии счётчиков —
  двухцветная полоса (зелёный succeeded + красный failed на сером треке) + подпись `✓ X  ✗ Y  / N`.
- Timeline получает события `{t: "job_progress", id, progress: 0.5, msg: "...", succeeded, failed, total}` через WS firehose, обновляет live

### 22.4. Per-type stats в UI (avg/min/max/p95) — shipped (Phase 3)

В дополнение к Prometheus — простая агрегация в БД для быстрого взгляда без Grafana.

**Schema:** `duration_ms BIGINT NULL` + `started_at TIMESTAMPTZ NULL` на `job` (см. секцию 6). При финальном UPDATE state:
```sql
UPDATE job SET 
    state = 'SUCCEEDED',
    duration_ms = EXTRACT(EPOCH FROM (now() - started_at)) * 1000
WHERE id = :id;
```

**API:**
```
GET /api/stats/types?range=24h
→ [
    {
      type: "SendEmail", queue: "email",
      success_count: 1247, failed_count: 3, retry_count: 18,
      avg_duration_ms: 245, min_duration_ms: 120, max_duration_ms: 8400, p95_duration_ms: 580,
      paused: false
    },
    ...
  ]
```

**SQL:**
```sql
SELECT 
    payload_type, queue,
    count(*) FILTER (WHERE state='SUCCEEDED')                  AS success_count,
    count(*) FILTER (WHERE state='FAILED')                     AS failed_count,
    sum(attempts - 1)                                           AS retry_count,
    avg(duration_ms)::int                                       AS avg_duration_ms,
    min(duration_ms)                                            AS min_duration_ms,
    max(duration_ms)                                            AS max_duration_ms,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)::int AS p95_duration_ms
FROM job
WHERE updated_at > now() - :range::interval
  AND duration_ms IS NOT NULL
GROUP BY payload_type, queue;
```

**UI:** страница `/types` (та же что для pause) — таблица с этими колонками, range selector (1h / 24h / 7d / 30d), sort by avg/p95/failure_rate.

### 22.5. Prometheus metrics endpoint (для Grafana) — shipped (Phase 3)

Micrometer + Prometheus registry, `/metrics` endpoint в scheduler-infra:

```kotlin
schedulerInfraModule {
    metrics {
        enabled = true
        endpoint = "/metrics"
        // или disabled()
    }
}
```

**Метрики:**

| Имя | Тип | Лейблы | Что |
|---|---|---|---|
| `scheduler_jobs_total` | Counter | `type, queue, result` | Завершённые (result = SUCCEEDED / FAILED / CANCELLED) |
| `scheduler_jobs_in_state` | Gauge | `state, queue` | Snapshot по состояниям |
| `scheduler_job_duration_seconds` | Histogram | `type, queue` | Латенция (с buckets для percentiles) |
| `scheduler_queue_depth` | Gauge | `queue` | ENQUEUED + PROCESSING |
| `scheduler_workers_alive` | Gauge | `node_id` | Через `info` метрику |
| `scheduler_retry_total` | Counter | `type, queue` | Retry events |
| `scheduler_idempotency_dedup_total` | Counter | `action` | `IdempotencyStore.tryMark` вернул false |

**Готовый Grafana dashboard JSON** в `docs/grafana-dashboard.json`. Импортируется в Grafana → графики из коробки: throughput, success rate, latency p50/p95/p99, queue depth, worker health.

Прометей пополняется через скан БД (раз в `metricsCollectInterval`, дефолт 10s) + event listeners на job state transitions.

### 22.7. Cancellation in PROCESSING state — shipped (Phase 3)

Cancel из dashboard когда job уже выполняется на worker-е.

**Реализовано:** `requestCancellation` штампует `cancel_requested_at` и шлёт транзакционный
`NOTIFY job_cancel, '<jobId>'`. Каждый worker держит выделенное LISTEN-соединение
(`JobCancelListener`) и при сигнале отменяет корутину выполняющегося хендлера
(`activeJobs` map в `WorkerPool`). Кооперативный хендлер разворачивается на ближайшем
suspend point → CANCELLED. Некооперативный (CPU/blocking-loop) после `cancelGracePeriod`
форсится в FAILED ("force-cancelled"), его корутина утекает до естественного завершения.
Поллинг `JobContext.isCancellationRequested` остаётся как fallback, когда push потерян.

**Базовая семантика — Kotlin coroutines кооперативны:**
- Worker оборачивает handler в `workerScope.launch { handler.execute(ctx, payload) }`
- Cancel = `coroutineJob.cancel()` → `CancellationException` на ближайшем suspend point
- Handler с suspend IO / `delay()` / `yield()` — останавливается в N миллисекунд
- Handler с CPU-loop без yield — НЕ останавливается (рекомендуем документации: cooperative handlers)

**Detection — PG LISTEN/NOTIFY:**
Используем существующий механизм. Channel `job_cancel`:
- Dashboard `POST /api/jobs/{id}/cancel` → UPDATE job SET cancel_requested_at, cancel_requested_by → `NOTIFY job_cancel, '<job_id>'`
- Каждый worker LISTEN-ит — при получении проверяет `activeJobs[jobId]` → `coroutineJob.cancel()`
- Latency ~ миллисекунды

**Worker flow:**
```kotlin
// при pickup из Rabbit
val row = storage.fetchAndLock(jobId)
if (row.cancel_requested_at != null) {       // cancel пришёл до pickup
    storage.markCancelled(jobId, by = row.cancel_requested_by)
    return
}

val coroutineJob = workerScope.launch { handler.execute(ctx, payload) }
activeJobs[jobId] = coroutineJob

// в фоновой корутине LISTEN job_cancel:
val signal = listen("job_cancel")
val active = activeJobs[signal.jobId] ?: return    // не у нас
active.cancel()
withTimeoutOrNull(cancelGracePeriod) { active.join() }
if (active.isActive) {
    // handler не отреагировал — force kill (марка FAILED, корутина "утекает")
    storage.markFailed(jobId, "force-cancelled: handler did not respond within ${cancelGracePeriod}")
} else {
    storage.markCancelled(jobId)
}
```

**Edge cases:**

| Сценарий | Поведение |
|---|---|
| Cancel ДО pickup (ENQUEUED/SCHEDULED/AWAITING_*) | Immediate UPDATE state='CANCELLED'. Worker при pickup увидит state ≠ ENQUEUED → ack без выполнения |
| Cancel ВО ВРЕМЯ выполнения (PROCESSING) | NOTIFY → worker cancels → grace period → CANCELLED или FAILED |
| Worker умер до cancel signal | Orphan recovery → следующий worker видит cancel_requested_at != null → instant CANCELLED |
| Handler не cooperative | После grace period — state=FAILED с reason "force-cancelled". Корутина продолжает в JVM (leak) — это диагностируется операционно (но system state корректен) |

**Конфиг:**
```kotlin
schedulerWorkerModule {
    cancelGracePeriod = 30.seconds      // дефолт — match shutdown timeout
}
```

**DAG impact:** cancelled job обрабатывает dependents так же как failed (по `on_failure`: PROPAGATE_FAILURE → cancel cascade, CANCEL_CHILD → cancel, IGNORE → continue с decrement).

### 22.9. Payload schema evolution — shipped

> **Статус (2026-05-28):** forward-compat — `SchedulerCoreConfig.json` ставит
> `ignoreUnknownKeys = true` (добавление полей безопасно). Несовместимый payload
> (удалённое/переименованное обязательное поле, смена типа) теперь классифицируется как
> **терминальный, без retry**: `WorkerPool` ловит `SerializationException` в цепочке
> причин (и на decode-at-pickup, и из `handleFailure` — последнее покрывает function-ref,
> где args декодируются во время выполнения) и пишет FAILED с сообщением, называющим
> mismatch. Повторы бессмысленны — байты не изменятся. Покрыто
> `SchemaEvolutionIntegrationTest`. "Park" state — по-прежнему Phase 2; schema-hash алерты — **shipped** (см. ниже).

Реальная prod-проблема: в очереди тысячи jobs со старой схемой `SendEmail(userId, template)`, деплой с новой `SendEmail(userId, template, fromAddress)` — старые jobs не парсятся.

**Стандартизированный Json config** в нашей либе:
```kotlin
val json = Json {
    ignoreUnknownKeys = true          // удалённые поля игнорируются
    encodeDefaults = false             // не сериализуем default values (компактнее)
    classDiscriminator = "_type"       // для polymorphism
}
```

Это **не конфигурируется пользователем** — мы гарантируем правильное поведение из коробки.

**Правила эволюции (документируем в README):**

| Изменение | Что делать | Compat |
|---|---|---|
| Добавить поле | Дать default value: `val fromAddress: String = "..."` | ✅ backward |
| Удалить поле | `ignoreUnknownKeys=true` игнорирует. Сначала убрать из handler, потом из data class | ✅ forward |
| Переименовать | `@SerialName("old_name") val newName: String` | ✅ обе стороны |
| Изменить тип | НЕЛЬЗЯ плавно — V1/V2 pattern с отдельными handlers | ⚠️ breaking |

**V1/V2 pattern для breaking changes:**
```kotlin
@Serializable data class SendEmailV1(val userId: Long, val template: String) : Job
@Serializable data class SendEmailV2(val userId: Long, val template: String, val fromAddress: String) : Job

@Single(binds = [JobHandler::class]) @JobType(SendEmailV1::class)
class SendEmailV1Handler(...) : JobHandler<SendEmailV1>     // часто proxy на общую логику

@Single(binds = [JobHandler::class]) @JobType(SendEmailV2::class)
class SendEmailV2Handler(...) : JobHandler<SendEmailV2>
```

После catch-up очереди (все V1 jobs прошли) — удалить V1.

**DeserializationException → non-retriable FAILED:**
```kotlin
try {
    json.decodeFromString(payloadType, row.payload_json)
} catch (e: SerializationException) {
    storage.markFailed(jobId, "Cannot deserialize payload for $payloadType: ${e.message}")
    // не retry — ошибка схемы не исправится повторами
}
```

**Deploy ordering** для backward-compat изменений: сначала деплоить новые workers (умеют читать V1 и V2 через default values), потом старые отключать. Standard pattern.

**Schema-hash алерты — shipped (worker-side, проактивно поверх реактивного FAILED выше).** На старте воркер для каждого handled payload-типа считает хэш его `SerialDescriptor` (`SchemaHasher` в `core/backend`: serialName+kind+nullability, элементы сортированы по имени → реордер полей не триггерит, а add/remove/rename/retype/optional/nullable — да; guard против рекурсивных типов) и сравнивает с последним значением в таблице `payload_schema` (V5). Изменение с прошлого деплоя → `WARN` + опциональный хук `SchedulerWorkerConfig.onSchemaDriftAlert(payloadType, prev, cur)`. Best-effort — не блокирует старт (отсутствие V5 / незагружаемый класс логируются и пропускаются); дедуп по флоту естественный (первый воркер фиксирует новый хэш, остальные видят unchanged). Запускается в `WorkerPool.start()` до начала consume. Покрыто `SchemaHasherTest` (чувствительность хэша), `PayloadSchemaRepositoryIntegrationTest` (first-seen/unchanged/drift), `SchemaDriftCheckTest` (алерт только на дрейф).

**«Park» state — по-прежнему Phase 2** (требует отдельного `JobState` + миграции статус-машины; вместо FAILED парковать несовместимые jobs до ручного разбора).

### 22.10. DAG cycle prevention — shipped

> **Статус (2026-05-28):** циклы структурно невозможны (нет retroactive `addDependency`).
> Дедуп дубль-parents реализован двумя слоями, как в дизайне: `enqueueAfter` делает
> `waitFor.distinct()` (и `pendingDeps = distinct.size`), а `JobDependencyRepository.insert`
> использует `insertIgnore` (ON CONFLICT DO NOTHING) на композитном PK `(parent_id, child_id)`.
> `after(a, a)` теперь = одна зависимость. Покрыто `DagDedupIntegrationTest`.

В текущем API циклы **структурно невозможны:**
- Dependencies задаются при enqueue child-а
- Child может зависеть только от уже существующих parents
- Нет API для retroactive `addDependency(parent, child)`

DAG строится топологически снизу вверх. Цикл создать нельзя — A не может зависеть от B, потому что когда A enqueue-ился, B ещё не существовал.

**Дубликаты parents — единственный реальный риск:**
```kotlin
val a = scheduler.enqueue(JobA())
scheduler.enqueue(JobB()) { after(a, a) }   // дубликат
```
Naive INSERT даст `pending_deps_count = 2` → B никогда не стартует. **Дедупликация на двух уровнях:**

1. `after(...)` API делает `.distinct()` перед передачей в INSERT
2. PRIMARY KEY на `job_dependency(parent_id, child_id)` + `INSERT ... ON CONFLICT DO NOTHING`

**Зависимость от уже-terminal job:**
- Все parents в SUCCEEDED → child сразу `state=ENQUEUED, pending_deps=0`, в outbox
- Хоть один FAILED + `on_failure=PROPAGATE_FAILURE` → child сразу FAILED
- Хоть один CANCELLED + propagate → child сразу CANCELLED
- Иначе — стандартный pending_deps flow

Уже встроено в логику инициализации `pending_deps` при enqueue.

**Что НЕ делаем:** cycle detection algorithm (нечего детектить), retroactive addDependency API (открыло бы дверь циклам).

**Guardrails (shipped):** fan-in `enqueueAfter` (число distinct-родителей) и длина `chain` ограничены конфигурируемыми `SchedulerCoreConfig.maxDagFanIn` / `maxChainLength` (дефолт 1000) — fail-fast `IllegalArgumentException` ещё до записи в БД при runaway-форме (барьер на 100k родителей, цепочка на 100k шагов). Покрыто `DagIntegrationTest` (over/at-cap для обоих). Транзитивная **глубина** DAG и per-parent **fan-out** намеренно НЕ ограничиваются — потребовали бы обход графа / лишний COUNT на каждый enqueue, непропорционально для guardrail.

### 22.11. Context propagation (MDC + OpenTelemetry) — shipped

**Проблема:** HTTP handler с `MDC.put("user_id", "123")` enqueue-ит job → через 5 минут на worker-ноде логи handler-а не содержат `user_id`. Невозможно correlate.

**Решение:** capture контекст при enqueue, restore при выполнении.

**Capture (автоматический):**
```kotlin
suspend fun Scheduler.enqueue(job: Job, opts: EnqueueOptions = EnqueueOptions()): UUID {
    val captured = if (opts.captureContext) {
        ContextSnapshot(
            mdc = MDC.getCopyOfContextMap() ?: emptyMap(),
            traceparent = OtelContext.current().traceparent,
            tracestate = OtelContext.current().tracestate,
        ).filterMdc(config.mdcAllowList)
    } else null
    // → INSERT INTO job (..., context_json = captured.toJson())
}
```

**Schema:** `job.context_json JSONB NULL` (см. секцию 6). NULL — enqueued из cron/system context (нечего capture-ить).

```json
{
    "mdc": {"user_id": "123", "request_id": "abc-def"},
    "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
    "tracestate": null
}
```

**Restore на worker-е:**
```kotlin
suspend fun executeJob(jobId: UUID) {
    val row = storage.fetchAndLock(jobId)
    val ctx = row.context_json
    
    val span = tracer.spanBuilder("job.${row.payload_type}.execute")
        .setParent(extractOtelContext(ctx?.traceparent))
        .setAttribute("job.id", jobId.toString())
        .setAttribute("job.queue", row.queue)
        .setAttribute("job.attempt", row.attempts.toLong())
        .setAttribute("messaging.system", "taskscheduler")
        .startSpan()
    
    withContext(MDCContext(ctx?.mdc ?: emptyMap()) + OtelContext(span.context)) {
        try {
            handler.execute(ctx, payload)
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR)
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
}
```

`MDCContext` из `kotlinx-coroutines-slf4j` — coroutine-safe propagation MDC. Любой `log.info()` внутри handler видит `user_id=123`.

**OpenTelemetry: depend on API only:**
- `io.opentelemetry:opentelemetry-api` (lightweight, ~50KB) — обязательная dep у нас
- `io.opentelemetry:opentelemetry-sdk` — приносит пользователь если нужен export в Tempo/Jaeger
- Без SDK → API возвращает no-op tracer, наш код не падает

Чистая интеграция без принуждения.

**Config:**
```kotlin
schedulerCoreModule {
    contextPropagation {
        captureMdc = true              // default
        captureOtel = true             // default
        mdcAllowList = null            // null = все ключи (рекомендуем явный allowlist в prod docs)
    }
}

// opt-out на enqueue
scheduler.enqueue(SendEmail(...), EnqueueOptions(captureContext = false))
```

**MDC allowList — security:** в MDC могут быть auth tokens, секреты. Whitelist даёт явный контроль:
```kotlin
mdcAllowList = listOf("user_id", "request_id", "tenant_id")
```
NULL = пересылаем всё (default — для local dev). Production docs рекомендуют явный allowlist.

**OTel span attributes** (semantic conventions для messaging):
- `messaging.system` = "taskscheduler"
- `messaging.operation` = "execute"
- `job.id`, `job.type`, `job.queue`, `job.attempt`, `job.priority`

**Результат:** в Grafana Tempo / Jaeger trace показывает непрерывную картину `HTTP request → scheduler.enqueue → ... → job.execute (через 5 минут) → mailer.send → HTTP к SendGrid`. Один trace_id связывает всё.

### 22.12. Module impact

Часть фичей затрагивает все модули, часть локальны:

| Фича | Affected modules |
|---|---|
| Pause job types | `:engine-infra` (publisher проверка), `:engine-worker` (handler check), `:dashboard-server` (API), `:dashboard-web` (UI) |
| Node-pinning | `:transport-rabbit` (topology), `:engine-infra` (publisher routing), `:engine-worker` (auto-declare node/tag queues), `:dashboard-server`/`:web` (re-route UI) |
| Progress | `:core` (JobContext), `:engine-worker` (throttle), `:dashboard-server`/`:web` (UI progress bar) |
| Per-type stats | `:dashboard-server` (API), `:dashboard-web` (UI), `:storage-postgres` (aggregation SQL) |
| Prometheus metrics | `:engine-infra` (Micrometer setup), `:dashboard-server` (`/metrics` endpoint) — новая зависимость Micrometer |

---

## 23. Открытые вопросы / темы для обсуждения

(Будет дополняться по мере обсуждения.)

- [x] **Custom dispatcher per queue — shipped:** `queue("cpu", concurrency=4, dispatcher=Dispatchers.Default)` (см. 13.3, `CustomDispatcherIntegrationTest`).
- [x] **ArchivalSink — shipped:** file (`FileArchivalSink`) + S3-совместимый (`:archival-s3`, покрывает AWS S3 / MinIO / R2 / GCS-S3-API) бэкенды для архива удаляемых jobs перед DELETE (см. 18.7). Архив `job_event`/`outbox` — по-прежнему через custom sink.
- [x] **Priority inheritance в DAG — shipped:** `EnqueueOptions(inheritPriorityFromParents = true)` → `enqueueAfter` берёт `max(parent.priority)` (explicit priority выигрывает); покрыто `DagIntegrationTest` (см. 19.7).
- [x] **Adaptive prefetch + circuit breaker — shipped:** автоматическая адаптация под нагрузку (см. 20.7 / 20.8).
- [x] **Lambda capture — shipped:** K2 compiler plugin для `enqueueLambda { mailer.send(123) }` (см. 21.9; не KSP — KSP не переписывает call-site).

---

## История изменений

- **2026-05-23** — первичный дизайн зафиксирован.
- **2026-05-23** — добавлена секция 11 (RabbitMQ topology and deployment): custom Docker image с delayed-message plugin, hybrid scheduling (plugin для <24ч, PG fast-forward для ≥24ч), payload в Rabbit = только job_id, amqp-client как клиент.
- **2026-05-23** — добавлена секция 12 (Koin integration): scheduler-модули как DSL с builder lambda, handler регистрация через `@Single(binds=[JobHandler::class]) + @JobType(...)`, lifecycle explicit start/stop + опциональный KTOR plugin.
- **2026-05-23** — добавлена секция 13 (Worker pool, lifecycle): per-queue scope с per-queue concurrency, heartbeat-based locking (90s lock + 30s heartbeat, один UPDATE на все in-flight), graceful shutdown 30s.
- **2026-05-23** — **архитектурный пивот**: split deployment (секция 14). User app (workers + handlers) и scheduler-infra container (наш Docker image: outbox, recurring, fast-forward, safety-net, dashboard) — отдельные процессы. Single replica infra + restart-on-fail. Module split: `:engine-worker` (user app) vs `:engine-infra` + `:standalone-runner` (infra container). Flyway migrations — infra container владелец схемы, user app fail-fast при mismatch.
- **2026-05-23** — добитa секция 9 (Dashboard): firehose WebSocket с invalidation signals (детали GET-ом), PG LISTEN/NOTIFY как distributed pub/sub источник events, auth DSL (basic + none + custom), manual retry сбрасывает attempts=0, frontend routes (/jobs, /jobs/{id}, /recurring, /workers, /).
- **2026-05-23** — добавлена секция 15 (Compose Multiplatform Web setup): `:shared-model` становится KMP (jvm + wasmJs), `:dashboard-web` на CMP с Material3 + androidx.navigation 2.8 + ktor-client wasmJs, copy task для подключения bundle в `:dashboard-server`, webpack proxy для dev workflow (HMR + same-origin), theme auto+toggle с localStorage persistence, bundle ~5-8 МБ gzipped, требует Wasm GC браузеры (Chrome 119+, Firefox 120+, Safari 18.2+).
- **2026-05-23** — добавлена секция 16 (Retry policy): дефолт exp backoff 1s/2x/1h с max_attempts=3 и full jitter; non-retriable через `NonRetriableJobException` + global `notRetriableOn` predicate; per-handler property override + per-enqueue override (priority enqueue > handler > global); `onFinalFailure` hook в JobHandler для алертов; long backoff (>24ч) автоматически переходит в SCHEDULED → fast-forward.
- **2026-05-23** — добавлена секция 17 (Idempotency): at-least-once гарантия (exactly-once невозможен), `JobContext` с `ctx.jobId` в handler API (секция 12.4 обновлена), встроенный `IdempotencyStore` (PG-backed default через `idempotency_log` таблицу, pluggable), `enqueueOnce(key, job)` для producer-side dedup через `idempotency_key` column + unique partial index, DAG защищён existing optimistic locking. JobHandler signature обновлена: `execute(ctx, job)` и `onFinalFailure(ctx, job, error)`.
- **2026-05-23** — добавлена секция 18 (Audit & retention): RetentionCleanup task в engine-infra, дефолты succeeded=7d / failed=30d / cancelled=7d / idempotency_log=30d (independent TTL); CASCADE на job_event/job_dependency/outbox при удалении job, idempotency_log без CASCADE; `actor` column в job_event для MANUAL_* event types из dashboard, actorExtractor lambda для custom auth; archival (S3/cold storage) — Phase 2.
- **2026-05-23** — добавлена секция 19 (Priority API): range 0..10 (Rabbit native priority queues, x-max-priority уже в topology); override chain enqueue > handler > queue > 0; `defaultPriority` property в JobHandler; per-queue defaultPriority в worker module; priority columns добавлены в outbox и recurring_job; DAG inheritance НЕ наследуем (priority=0 у child по дефолту), для chain — helper `chain(..., priority=9)`; priority сохраняется через delayed exchange.
- **2026-05-23** — добавлена секция 20 (Backpressure): естественный backpressure через prefetch уже работает по дизайну; lazy queues mode (`x-queue-mode: lazy`) опционально per-queue для high-volume; alert hook `onQueueDepthAlert` с per-queue thresholds, periodic check в engine-infra (1 min); auto-reject и auto-scaling НЕ в MVP (user-side / external infra); Prometheus metrics + adaptive prefetch + circuit breaker — Phase 2.
- **2026-05-23** — добавлена секция 21 (Function reference API): typed overloads KFunction1..KFunction6 (target + 0..5 args); payload kind="function_ref" с target_type/qualifier/method_signature/args; multiple bindings → fail-fast при enqueue, override через `EnqueueOptions(targetQualifier)` или subclass reference; args требуют kotlinx-serialization @Serializable, fail-fast при enqueue если нет; sealed-class — главный путь (стабилен к refactor), function-ref — для ad-hoc/коротких jobs; lambda capture через KSP — Phase 2.
- **2026-05-23** — добавлена секция 22 (Operational features): (1) pause/disable job types из UI через таблицу job_type_pause + проверка в outbox publisher и worker, unpause = catch-up без throttle; (2) node-pinning + tags: `target_node`/`target_tag` колонки + auto-declare `q.node.X`/`q.tag.Y` + routing в publisher; (3) progress reporting через `JobContext.updateProgress(progress, msg)` с throttle 1s, columns progress/progress_msg на job, WS event для live UI; (4) per-type stats в UI (avg/min/max/p95) через `duration_ms` column + agg SQL, страница /types; (5) Prometheus `/metrics` endpoint через Micrometer + готовый Grafana dashboard JSON. Schema обновлена (target_node, target_tag, progress*, duration_ms, started_at columns + job_type_pause table). JobContext стал interface (был data class) для поддержки updateProgress.
- **2026-05-23** — расширения по 5 advanced топикам: (1) **cron timezone** per-recurring (IANA TZ, NULL=UTC) + **misfire policy** (CATCH_UP_ONE default, SKIP, CATCH_UP_ALL); recurring_job schema columns timezone/misfire_policy/target_node/target_tag; (2) **cancellation в PROCESSING** (22.7): PG LISTEN/NOTIFY на channel `job_cancel`, grace period 30s, mark FAILED если handler не respond, schema columns cancel_requested_at/cancel_requested_by; (3) **payload schema evolution** (22.9): стандартизированный Json config (ignoreUnknownKeys=true, encodeDefaults=false), правила add/remove/rename/V1-V2, DeserializationException → non-retriable FAILED; (4) **DAG cycle prevention** (22.10): циклы структурно невозможны в API, dedup через ON CONFLICT DO NOTHING + `.distinct()`; (5) **context propagation** (22.11): MDC + OTel (API only dependency), capture при enqueue (captureContext=true default), restore через withContext(MDCContext + OtelContext), schema column context_json, mdcAllowList для security.
- **2026-05-24** — **рефакторинг структуры модулей** (секция 3 переписана): (1) `:shared-model` → `:core:shared`, добавлены `:core:backend` (JVM-only утилиты: BaseUseCase, runCatchingWithLogging, ApiResponse, Ktor *Handle, TimeProvider, метрики) и `:core:frontend` (wasmJs-only: BaseComponent, theme, ApiClient, общие Composable примитивы) — пакетная организация по образцу `cs.trade.core` из основного проекта пользователя; (2) принята **3-layer convention** (`api/domain/infrastructure`) как внутренняя пакетная организация в backend Gradle-модулях — полный набор слоёв только в `:dashboard-server` (единственный с HTTP/WS), `:engine-*` без `api/`, правило "1 функция репо ↔ 1 UseCase" применяется только в `:dashboard-server` (CRUD); (3) UI-структура `:dashboard-web` — `data/domain/presentation` (как в основном проекте), но с **Decompose 3.x** для навигации вместо androidx.navigation-compose: Component-pattern (interface + DefaultImpl) заменяет ViewModel, `Value<T>` заменяет StateFlow, `viewmodels/` и `state/` папок нет (растворены в `screens/{name}/`), есть `presentation/{root,screens,components,theme}`; (4) обоснован trade-off Gradle submodule vs пакет — 3 реальные причины (deploy classpath isolation, разные compile targets KMP/jvm-only/wasmJs-only, потенциальная independent версия для Maven). Все упоминания `:shared-model` в DESIGN.md обновлены на `:core:shared`.

- **2026-05-26** — **Phase 2 implementation closed** (operability + UX batch). Доставлены и привязаны к разделам выше:
  - **14.3 Distribution / leader election**: `LeaderElection` через `pg_try_advisory_lock` на выделенном raw JDBC-коннекте (вне Hikari, чтобы не съесть pool slot); StateFlow `isLeader`, gating для OutboxPublisher / RecurringScheduler / FastForwardTask / RetentionCleanup; release() в shutdown hook с graceful 30s drain.
  - **9 Dashboard / events**: `PostgresEventBus` поверх pgjdbc `PGNotificationListener` + `LISTEN/NOTIFY` на канале `scheduler_event`; backplane между infra-репликами без Rabbit fanout, поверх него — WS firehose в `:dashboard-server` через `EventsRouting`.
  - **13 Worker lifecycle**: `withTimeout(JobConfig.executionTimeout, default = 5min)` вокруг `JobHandler.execute`; `TimeoutCancellationException` → retry policy как обычная ошибка; graceful shutdown 30s, после — cancel + ack-on-cancel чтобы Rabbit не передоставил.
  - **20 Backpressure**: outbox publisher переключён на bounded channel (capacity = batch×2) + suspend на `send`, так что INSERT-ы в outbox естественно тормозятся при затыке Rabbit; раньше был unbounded list + risk OOM.
  - **6 Schema / 12.6 fail-fast**: user app при старте сверяет `flyway_schema_history.checksum` vs ожидаемые версии миграций storage-postgres; mismatch → `IllegalStateException` с подсказкой "обновите scheduler-infra".
  - **22.6 Manual reroute**: добавлен `Scheduler.reroute(jobId, targetNode, targetTag, by): RerouteResult` + `JobRepository.updateRouting` (CAS на `(id, version, state ∈ ACTIVE)`); в одной tx обновляет targetNode/targetTag, bump version, пишет `MANUAL_REROUTE` job_event и outbox-row с новым routing key. Дублирующая старая Rabbit-message обезвреживается pickup CAS на `locked_until` (at-most-once execution). REST: `POST /api/jobs/{id}/reroute?targetNode=&targetTag=&by=` → 200 REROUTED / 409 ALREADY_TERMINAL|CONFLICT / 404 NOT_FOUND. UI: кнопка "Re-route" в JobDetail на не-terminal состояниях, expandable form с двумя полями + Apply.
  - **Operability endpoints**: `/health/leader` (`{leader: bool}`, всегда 200); `/health/ready` теперь оборачивает Rabbit ping в `withTimeout(2.seconds)` + `Dispatchers.IO` так что повисший broker не блокирует Netty event loop.
  - **UI (dashboard-web)**: dark mode (localStorage > `prefers-color-scheme`, toggle в toolbar); freeform job-id поиск в nav bar; `payloadType` + `queue` фильтры в JobList; cursor-style пагинация (page/size с persistence); фильтры сохраняются в `BrowserStorage`; mobile responsive horizontal scroll для широких таблиц.
  - **Не реализовано (Phase 3 / на будущее)**: KSP lambda capture (раздел 21), ArchivalSink (18), adaptive prefetch + circuit breaker (20), custom dispatcher per queue (13), priority inheritance в DAG (19).

- **2026-05-27** — Phase 3 batch (operability + long-running UX):
  - **18.7 ArchivalSink** — shipped. `ArchivalSink` interface (Noop default Koin binding + reference `FileArchivalSink` writing JSONL per-day partitions). `RetentionCleanupBatchUseCase` calls `archive(...) → DELETE` in that order; sink throws → DELETE skipped for that batch, rows survive into next tick. Only `job` row is archived; `job_event/job_dependency/outbox` are CASCADE'd.
  - **22.3 Long-running progress** — shipped end-to-end. `JobContext.updateProgress(progress, msg)` with 1s per-invocation throttle in `JobContextImpl`, `ReportProgressUseCase` writes `job.progress/progress_msg/progress_updated_at` (state-scoped to PROCESSING — late writes after terminal are silently dropped) + emits `WebSocketEvent.JobProgress`. PG LISTEN/NOTIFY backplane fans to all replicas, dashboard WS firehose pushes to clients. UI: `JobDetail` shows `LinearProgressIndicator` + label, `JobList` row carries a thin progress bar under the State chip (only PROCESSING + progress != null). Both screens update in-place from WS — no REST refetch on every tick.
  - **20.7 Adaptive prefetch** — shipped. Per-queue `AdaptivePrefetch(targetLatency, minPrefetch, maxPrefetch, tuneInterval, sampleWindowSize)` config on `schedulerWorkerModule { queue(...) }`. `PrefetchTuner` keeps a rolling latency window per queue, runs in a background coroutine, calls `ConsumerHandle.setPrefetch(N)` on overload (p95 > target × 1.5 → halve) or idle (p95 < target × 0.5 → additive bump). `channel.basicQos(N)` is live-update — no consumer restart. Sample fed from `WorkerPool.processLocked`'s finally block, same site as `metrics.recordExecution`. Bounded by min/max, dead-band [0.5×, 1.5×] target. Default impl of `ConsumerHandle.setPrefetch` is a no-op so non-Rabbit transports ignore it.
  - **Test infra (#274 fallout)**: `EXTERNAL_RABBIT_HOST` env-bypass pattern (analogous to `EXTERNAL_PG_URL`) for `scheduler-test-rabbit` container — Testcontainers Rabbit can't start on Docker-Desktop dev boxes where the daemon API returns a stub for non-CLI clients. 5 Rabbit-using tests migrated. Pre-existing nested-class `Class.forName` bug surfaced and fixed by lifting `@Serializable data class` Job declarations to file-level (same pattern as `WorkerTimeoutIntegrationTest.HangingJob`).
  - **Dashboard auth fix**: `Routing.configureDashboardRouting()` → `Route.configureDashboardRouting()`. The previous `Routing.()` receiver escaped the `authenticate("dashboard") { ... }` lambda's `Route` scope, so `/api/jobs` etc. were silently public. Now genuinely gated; `BasicAuthGatingIntegrationTest`'s regression assertion flipped from 200 → 401.
  - **Test isolation fixes**: `SafetyNetIntegrationTest` got `@BeforeEach` truncate of `job/outbox` (shared `scheduler-test-pg` was polluting `findOrphaned(...)` counts); `EnqueueIntegrationTest` and `OutboxPublisherIntegrationTest` scoped their "exactly N outbox rows" assertions to `jobId`-filtered counts rather than global. JSONB whitespace assertions (`{"userId":123}` vs `{"userId": 123}`) replaced with regex-tolerant matchers in `EnqueueIntegrationTest` / `RecurringIntegrationTest`.

- **2026-05-29** — **S3-compatible `ArchivalSink` shipped** (18.7). New opt-in module `:archival-s3` with `S3ArchivalSink` on AWS SDK v2 (sync `S3Client` over `url-connection-client`, netty excluded). Writes each retention batch as one JSONL object at `s3://<bucket>/[<prefix>/]<category>/<day>/<sha256>.jsonl`; works against AWS S3 / MinIO / Cloudflare R2 / GCS-S3-API / DO Spaces via `endpoint` override + path-style (`create(bucket, region, endpoint, accessKeyId, secretAccessKey, …)`). Content-derived key (records sorted by id, day from max `updatedAt`, name = SHA-256 of bytes) makes a retry of the same batch overwrite rather than duplicate — matching the retention loop's skip-DELETE-on-failure replay. AWS SDK isolated in its own module so core stays SDK-free; user binds `single<ArchivalSink> { S3ArchivalSink.create(...) }` after `schedulerInfraModule`. `S3ArchivalSinkIntegrationTest` covers round-trip + idempotency + per-category prefixes against MinIO (EXTERNAL_S3_ENDPOINT bypass + Testcontainer fallback).
- **2026-05-29** — **standalone-runner S3 archival env-wiring** (14.5). The prebuilt scheduler-infra image now bundles `:archival-s3` and switches the sink on via `ARCHIVE_S3_*` env (bucket/region/endpoint/creds/path-style/key-prefix) — `RunnerConfig.ArchiveS3Config.fromEnv` parses it (null when `ARCHIVE_S3_BUCKET` unset → `Noop`), `validate()` rejects a scheme-less endpoint and a half-set credential pair, and `archivalS3Module` binds `S3ArchivalSink` over the Noop default (Koin last-wins) only when configured. Shutdown closes the sink's S3 client. `RunnerConfigArchivalTest` covers env parsing + validation. Core library modules stay SDK-free — only the deployment artifact carries the AWS SDK.
- **2026-05-29** — **`CATCH_UP_ALL` misfire policy implemented** (was treated as `CATCH_UP_ONE` + WARN). `CronExpr.catchUpPlan(expression, firstMissed, now, tz, limit)` walks cron slots from `nextTriggerAt` forward, counting every missed slot `<= now` (returns occurrences + next trigger + capped flag); pure + unit-tested (6 cases incl. cadence, cap-parking, tz). `FireDueRecurringJobsUseCase` now plans per-policy and inserts one `job`+outbox per missed slot, all under the single `markFiredAndScheduleNext` CAS (losing replica rolls back the whole batch). Bounded by `MAX_CATCH_UP_PER_TICK=500`/tick — overflow parks `next_trigger_at` at the first un-fired slot so the remainder catches up on later ticks (no drop, no transaction flood). `RecurringIntegrationTest` adds a CATCH_UP_ALL-vs-CATCH_UP_ONE contrast on an identical 5-minute backlog. DESIGN.md 8.5 updated. This closes the last "not implemented in MVP" marker in live code.
- **2026-05-29** — **"Retry +1" dashboard button shipped** (9.5). New `RetryMode {FRESH_BUDGET, ONCE}` (`core/shared`) threads through `Scheduler.retry(jobId, by, mode)` → `JobRepository.manualRetry(..., mode)`. `FRESH_BUDGET` keeps the existing reset-to-0 ("Retry"); `ONCE` parks `attempts = max_attempts - 1` so the next pickup bumps it to the ceiling and the worker's `attempts < maxAttempts` gate fails the instant the run errors — exactly one more execution, then back to FAILED, no auto-retry storm (deterministic whether the row reached FAILED via exhausted retries or a non-retriable/schema error). Distinct `MANUAL_RETRY_ONCE` audit event. Wired end-to-end: `POST /api/jobs/{id}/retry?mode=once`, web `JobsRepository.retry(mode)`/`RetryJobUseCase`, `JobDetailComponent.onRetryOnceClicked`, and a secondary OutlinedButton in `JobDetail` (FAILED only). `JobRepositoryCasIntegrationTest` adds the ONCE case (attempts = max-1 + event); the worker `attempts < maxAttempts` gate is already covered by `RetryIntegrationTest`. One CAS-UPDATE; `max_attempts` is immutable post-enqueue so reading it in-transaction is race-free.
- **2026-05-29** — **DependencyGraph UI shipped** (9.6 — the last substantive open Phase-2 item). JobDetail now renders the job's transitive DAG instead of two flat parents/children lists. Server: `GetJobDetailUseCase` BFS-walks the connected component in both directions over `JobDependencyRepository` (reusing the existing 1-hop `findParentsOfChild`/`findChildrenOfParent` — no new SQL), de-duping edges via `JobDependency` value-equality and filtering dangling edges; bounded by `MAX_GRAPH_NODES=100` with a `truncated` flag. New `JobGraph{nodes, edges, truncated}` + `JobGraphEdge{parentId, childId, onFailure}` DTOs (`core/shared`) replace `JobDetail.parents`/`children`; `JobApiMapper.toDetail` builds them. Web: new `DependencyGraph` Composable does longest-path (Kahn) layering + barycentre within-level ordering, draws parent→child edges with arrowheads on a `Canvas` (coloured by `on_failure`, legend for policies present) behind state-coloured node cards; focal node highlighted, non-focal nodes click through to their detail; horizontal-scroll only (lives inside JobDetail's vertical scroll). Section rendered only when the graph has edges. `DependencyGraphIntegrationTest` covers chain (ancestors+descendants), diamond (single node for two inbound paths + both converging edges), and standalone (single-node/zero-edge). No new endpoint/navigation — the graph ships inside the existing `GET /api/jobs/{id}` payload. `sortedMapOf` avoided in the layout (JVM-only — not on wasmJs).
- **2026-05-29** — **Subscribe-with-query on the WS firehose shipped** (9.2). `/api/ws/events` now accepts repeatable `jobId` / `queue` / `type` / `eventType` query params; the server builds an `EventFilter` (`core/shared` — conjunctive across dimensions, disjunctive within, empty = match-all; `matches()` via an exhaustive `when` over `WebSocketEvent` subtypes so a new event kind can't slip past) and forwards only matching events. Filtering moved from client to server. Backward-compatible: no params → the original firehose. Client: `EventStream.subscribe(filter)` opens a SEPARATE server-side-filtered socket (own backoff; doesn't touch the connection badge the shared socket owns); `DefaultJobDetailComponent` uses it with `jobIds={jobId}` for progress instead of sifting the whole firehose locally — the shared broad socket stays for pause events and JobList (which reacts to nearly every event type, so its union of interests is broad). `EventSubscriptionFilterTest` adds 5 `matches()` algebra cases + an end-to-end WS test (`?jobId=` → only the target job's events arrive, others dropped — proven by ordering). No new deps; param URL-encoding via ktor's `url.parameters.append`.
- **2026-05-29** — **`chain(vararg jobs, priority)` helper implemented** (19.7). `Scheduler.chain` gained a trailing `priority: Int? = null` named param that pins every step's priority in one call — the DESIGN 19.7 example (`chain(…, priority = 9)`) was aspirational; `chain` previously enqueued each step with bare defaults. `null` keeps the per-step handler/queue/global default (backward-compatible — no-arg `chain()` unchanged), a non-null value sets all steps. Impl threads one `EnqueueOptions` through the first `enqueue` + each `enqueueAfter`. `DagIntegrationTest` adds two cases (priority on every step / null → default). Orthogonal to `enqueueAfter`'s `inheritPriorityFromParents` (max-of-parents) — this is the simpler whole-pipeline override.
- **2026-05-29** — **DAG fan-in / chain-length guardrails** (22.10). New `SchedulerCoreConfig.maxDagFanIn` + `maxChainLength` (default 1000) fail-fast with `IllegalArgumentException` at enqueue — before any DB write — when `enqueueAfter` waits on more than N distinct parents or `chain` is given more than N steps, capping runaway/accidental shapes (a barrier on 100k parents, a 100k-step chain). The fan-in check runs after `waitFor.distinct()` so `after(a, a)` counts as one. `DagIntegrationTest` adds over-cap + at-cap cases for both. Transitive DAG **depth** and per-parent **fan-out** are intentionally left unbounded (they'd cost a graph walk / extra COUNT per enqueue — disproportionate for a guardrail); 22.10's "что НЕ делаем" updated to say so.
- **2026-05-29** — **Schema-hash drift alerts shipped** (22.9 — the proactive half; the reactive "bad payload → terminal FAILED" was already in). New `SchemaHasher` (`core/backend`) fingerprints a payload type's `SerialDescriptor` — sensitive to add/remove/rename/retype/optional/nullable, insensitive to field reorder (sorted by name), cycle-guarded. New `payload_schema` table (V5 migration) + `PayloadSchemaRepository.recordAndDetect` (first-seen / unchanged / changed, ON CONFLICT DO NOTHING for concurrent fleet startup). `SchemaDriftCheck` runs in `WorkerPool.start()` (new nullable ctor param, so the many direct-construction tests are untouched): for each `HandlerRegistry.knownPayloadTypes` it hashes via `serializer().descriptor`, compares, and on drift WARNs + invokes the optional `SchedulerWorkerConfig.onSchemaDriftAlert(payloadType, prev, cur)` hook. Best-effort (never blocks startup); fleet-dedup is natural (first worker records the change, the rest see it unchanged). Tests: `SchemaHasherTest`, `PayloadSchemaRepositoryIntegrationTest`, `SchemaDriftCheckTest`. "Park" state stays Phase 2 (needs a new JobState + state-machine migration).
- **2026-08-03** — **Python SDK shipped** (`clients/python`). Non-JVM services now speak the wire protocol directly instead of proxying through a JVM: `Scheduler` writes the same `job` + `outbox` + `job_event` rows in one transaction, `WorkerPool` consumes `q.<name>` (16-byte UUID body), claims rows with the same conditional UPDATE (`ENQUEUED|AWAITING_RETRY` + `pending_deps=0`), renews leases in one bulk statement, and finalises terminal transitions together with the DAG cascade in a single transaction. Covers enqueue / scheduleAt (incl. the 24h fast-forward split) / enqueueOnce (SKIP·REPLACE·ENQUEUE_AFTER over the V8 slot indexes) / chain / barriers / recurring registration / cancel·retry·delete, plus progress reporting, cooperative + push cancellation (`job_cancel` LISTEN), type-pause deferral, worker registry rows and `scheduler_events` NOTIFY envelopes so Python jobs appear live on the dashboard. asyncio throughout (psycopg3 + aio-pika). Deliberately **not** implemented: the outbox publisher, recurring firing, orphan recovery, retention and Flyway migrations — those stay owned by `scheduler-infra`, and the client fail-fasts if the schema is below V8. `payload_type` is a Python FQN by default (`@job_type` pins it), so cross-language execution is opt-in and each language should get its own queues. Note for future work: DESIGN 11.4 still describes the Rabbit body as a 36-byte UTF-8 string — the code has used 16 raw bytes since MVP, and the Python client follows the code.
- **2026-08-05** — **Исправлены два расхождения документа с кодом** (11.4, 21.3/21.4). **11.4**: тело Rabbit-сообщения — 16 сырых байт `Uuid.toByteArray()`, а не «UUID как UTF-8 строка, ~36 байт»; так было с самого MVP (`RabbitJobTransport`, `UUID_BYTES = 16`, зафиксировано `RabbitJobTransportTest`), и клиент, написанный по старой формулировке, молча уезжал бы в DLX (consumer строго проверяет длину). Заодно дописаны `priority` в properties и 32-битность `x-delay` (потолок плагина ~24 дня — причина, по которой fast-forward окно 11.5 равно 24ч). **21.3/21.4**: `FunctionRefPayload` сериализуется в camelCase (`targetType`/`targetQualifier`/`methodSignature`/`args`), поля `kind` в теле нет вовсе — дискриминатором служит колонка `payload_type = "function_ref"`, а `targetQualifier` при `null` в JSON отсутствует (`encodeDefaults = false`); псевдокод резолюции в 21.4 приведён к реальным именам и указывает на `FunctionRefRunner`. В оба раздела добавлены ссылки на код и тест как на источник истины. Само поведение не менялось — правка только документационная.
- **2026-08-05** — **Dashboard переехал с Compose Multiplatform (Wasm) на Kotlin/JS + React** (секция 15 переписана, 4.4 и 9.6 обновлены). `:core:shared` и оба фронтовых модуля сменили target `wasmJs` -> `js(IR)`; UI рендерится в DOM через `kotlin-react` 19 + `kotlin-emotion`, версии wrappers пинятся BOM'ом `2026.6.1` (последний релиз на Kotlin 2.3.21 — klib более новых нечитаем нашим компилятором). **Что не менялось:** слои `data/` + `domain/` и весь Component-слой Decompose (~1300 строк: навигационный стек, стейт экранов, фильтры, сортировка, web-history) переехали дословно — Decompose core UI-агностичен, а связка с React это один хук `useValue` на `useSyncExternalStore`. **Что переписано:** `*Content.kt` всех восьми экранов, общие компоненты и тема (~4400 строк). Material3 заменён собственной дизайн-системой в `:core:frontend` (`theme/` — палитра "Graphite" как CSS-переменные + типографика на IBM Plex; `ui/` — Button/Input/Select/Checkbox/Switch/Chip/ToggleChip/Menu/Panel/Spinner/EmptyState + табличные примитивы + SVG-иконки). Тема переключается записью `data-theme` на `<html>` — без ре-рендера дерева. Compose-`Canvas` (иконки, донат статистики, DependencyGraph, прогресс-бары) стал inline-SVG и CSS. Сборка: `dist/wasmJs/productionExecutable` -> `dist/js/productionExecutable`, `wasmJsBrowserDistribution` -> `jsBrowserDistribution`, `kotlin-js-store/wasm/yarn.lock` -> `kotlin-js-store/yarn.lock`; из каталога и buildSrc удалены Compose-плагины и артефакты, `compose-multiplatform.gradle.kts` заменён на `kotlin-js-react.gradle.kts`. Бандл: **~13 МБ -> ~2.5 МБ** (1.7 МБ JS + 0.8 МБ шрифтов), требование Wasm GC к браузеру снято. Шрифты IBM Plex переехали из `composeResources` в `jsMain/resources/fonts` и подключаются `@font-face` из `index.html` (грузятся параллельно с бандлом). Экранных изменений не задумывалось: набор экранов, фильтры, действия и REST/WS-контракты те же.
- **2026-08-05** — **Правки дашборда по итогам первого просмотра React-версии** (4 штуки). **(1) Типографика**: вся шкала поднята на 2 пункта вместе с line-height (body 12→14, mono 12→14, label 11→13, title 16→18 и т.д.). Прежняя была унаследована от Material — она mobile-first, а оператор читает эти таблицы на десктопе весь день. Ширины колонок с абсолютными датами расширены под новый кегль (170→195px), иначе «01.06.2026 14:30:05» упирался в край ячейки. **(2) Прибытие строк в Jobs**: новая джоба под дефолтной сортировкой встаёт наверх — раньше она просто возникала посреди списка. Теперь съезжает сверху с кобальтовой подсветкой, гаснущей за полсекунды. Механика опирается на то, что React монтирует DOM-узел только для нового `key = job.id`, поэтому CSS-анимация сама попадает ровно на новые строки; подавлять пришлось только первый кадр (`useSettled` + `key` блока строк по параметрам запроса) — иначе при смене фильтра/страницы анимировалась бы вся таблица. Уважает `prefers-reduced-motion`. **(3) Прогресс-бары** (JobDetail и мини-бар в JobList) получили `transition: width` — при очередном отчёте заполнение доезжает, а не телепортируется. Сегменты теперь смонтированы всегда (при нулевой ширине), иначе красный сегмент «провалов» появлялся бы сразу нужного размера мимо анимации. **(4) Recurring: Last run** — см. 9.6. Новая колонка показывает состояние живого (или последнего) запуска определения и его прогресс, клик по строке открывает этот job. Потребовало связи `job → recurring_job`, которой не было: колонка `job.recurring_id` (**миграция V9**, nullable, без бэкфилла), её проставляют оба пути запуска — плановый и ручной «Run now». Листинг остаётся двумя запросами: определения + `findLatestRunsByRecurringIds` (`DISTINCT ON (recurring_id)`, живые раньше терминальных, затем `created_at DESC`) вместо N+1. `RecurringJobDto.lastRun` — новое опциональное поле (`RecurringRunDto`), старые клиенты не ломаются. Покрыто `RecurringRunsIntegrationTest` (живой запуск побеждает более новый завершённый; фолбэк на последний завершённый; батч по нескольким определениям; one-off job игнорируются).
- **2026-08-05** — **Четыре правки дашборда** (0.8.1), из них одна — настоящий баг. **(1) Прогресс завершённой задачи не доходил до конца.** Оператор видел зелёный SUCCEEDED и полосу на 312 из 314 — выглядит как недоделанная работа под успешным статусом. Причина: записи прогресса троттлятся (1/сек), а counting-бар форсирует запись только на сэмпле, который достигает `total`. Хендлер, завершившийся отчитавшись по 312 из 314 (пропустил остальные, батчил хвост, вернулся раньше), оставляет последний сэмпл коротким. Теперь `finishTerminal` при переходе в SUCCEEDED дотягивает **только дробь** до 1.0 — и только если прогресс вообще отчитывался (иначе у каждого обычного хендлера появился бы бар из ниоткуда). Счётчики остаются ровно как отчитался хендлер: это его собственный учёт, переписывать его значит выдумывать работу. FAILED/CANCELLED не трогаем — там «докуда дошло» и есть диагностика. На фронте длина полосы теперь берётся из дроби, а разбивка на зелёное/красное — из счётчиков (`progressFill`); раньше длина считалась из счётчиков, из-за чего дотянутая дробь ничего бы не изменила. Покрыто `ProgressCompletionIntegrationTest`. **(2) Чекбоксы в Jobs**: кликабельной целью был сам `<input>` 14×14. Цель — `<label>` (связан с input вложенностью), поэтому «сделать больше» значит дать площадь метке. Метка растягивается на ячейку (`Checkbox.fillArea`), ячейка отдаёт padding (`TableCell.flush`), обёртка со `stopPropagation` растянута вместе с ними: раньше она обнимала только квадратик, и клик по краю ячейки проваливался в строку и **открывал задачу вместо выделения**. Квадрат 14→16px, колонка 44→52px, зона подсвечивается на hover. **(3) Длительности в подходящих единицах** (`formatDurationMs`): `5000ms` → `5s`, `200000ms` → `3m 20s`, `4320000ms` → `1h 12m`, `183600000ms` → `2d 3h`. Ниже секунды остаются миллисекунды; спутная единица печатается только когда не ноль (`3m 20s`, но `5m`); до 10 секунд сохраняется десятая (`5.2s`). В JobDetail и Type Stats (заголовки потеряли суффикс `ms`). Сортировка не изменилась — компараторы читают сырые миллисекунды. **(4) Одинаковая высота строк** (`TableRow.height`, 56px в Jobs и Recurring): строка PROCESSING несёт под чипом полосу прогресса и была выше соседних. Таблица с «плавающей» базовой линией плохо сканируется, а дёргалась она каждый раз, когда джоба входила в PROCESSING или выходила.
- **2026-08-05** — **Спокойное появление новых строк** (0.8.2). Слайд-ин, приехавший в 0.8.0, заодно заливал каждую новую строку цветом `primary-container`. На живой очереди задачи приходят пачками, и вспышка на каждой строке превращает верх таблицы в стробоскоп — хуже всего в тёмной теме, где этот токен насыщенно-синий. Заливка была добавлена сверх запроса: просили появление сверху, движение эту роль и выполняет. Осталось только оно, длительность 0.5→0.28с (полсекунды были нужны, чтобы вспышка успела погаснуть). Правило записано у кейфреймов: движение читается как «что-то приехало», цвет — как «что-то не так».

  Отдельной версией, а не внутри 0.8.1, потому что 0.8.1 к этому моменту уже был опубликован на PyPI (тег и артефакты соответствуют коммиту без этой правки), а перезалить занятую версию PyPI не даёт. Docker-образ `0.8.1` тоже собран без неё — актуальным становится `0.8.2`.

- **2026-08-08** — **Дашборд намертво вставал после хождения по цепочке задач** (0.8.3; 3.4, 9.6). Граф зависимостей на карточке джобы даёт кликнуть любой соседний узел; переход делался через `navigation.push(Config.JobDetail(id))`. В цепочке `A → B` это ломается со второго клика: из `A` открываем `B` (стек `[JobList, A, B]`), а граф на карточке `B` предлагает вернуться в `A` — тот же `Config.JobDetail(A)` уже лежит в стеке. Decompose такое отклоняет (`ChildrenNavigator.switch`: `Configurations must be unique`), и цена ошибки несоразмерна: `Relay` навигации запоминает исключение в поле `error` и **на все последующие события отвечает throw**. Поэтому умирал не только повторный клик по узлу — переставали работать вкладки верхнего меню, поиск по id и кнопка Back, причём молча и до перезагрузки страницы. Отсюда и описание бага «после перехода нельзя уйти никуда, даже в Jobs».

  Починка — не «не пускать в уже открытую джобу», а `pushToFront`: он убирает прежнее вхождение той же конфигурации и кладёт её наверх, так что стек уникален по построению, а история переходов сохраняется (`A → B → A` даёт `[JobList, B, A]`, Back ведёт в `B`, потом в список). Все пять точек входа на карточку джобы (клик в таблице Jobs, узел графа, Recurring, Upcoming, поиск по id) сведены в один приватный `openJob` — раньше каждая звала `push` самостоятельно, и любая новая точка входа повторила бы ту же ошибку. `@OptIn(DelicateDecomposeApi::class)` в `DefaultRootComponent` больше не нужен: `push` был единственным delicate-вызовом.

  Следом — **автопрокрутка графа к открытой джобе**. Уровень = колонка, поэтому 12-й шаг цепочки лежит примерно в 4500px правее нуля, а полоса при монтировании рисовалась со `scrollLeft = 0`: джоба, которую только что открыли, оказывалась за правым краем, и каждый шаг по цепочке стоил ручного протягивания. Теперь `useEffect` ставит `scrollLeft` так, чтобы фокус-карточка встала к левому краю с зазором в один `LEVEL_GAP`. Зависимость эффекта — **x фокус-узла, а не объект `layout`**: авто-рефреш карточки раз в N секунд приносит новый `JobGraph`, `useMemo` отдаёт новый объект, и по ссылочной зависимости граф дёргало бы обратно к фокусу прямо под руками у оператора, листающего его вручную. Прокрутка мгновенная, без `smooth`: анимировать нечего — предыдущей позиции у только что смонтированного узла нет.

- **2026-08-08** — **Переход в раздел после перезагрузки отматывал на одну задачу назад** (0.8.4; 3.4). Пройти по цепочке, нажать F5, нажать Jobs — и вместо списка открывалась предыдущая задача цепочки. Баг не в 0.8.3, а лишь **вскрыт** им: пока прогулка по цепочке вешала навигацию на втором клике, история глубже двух записей просто не набиралась.

  Механика. `DefaultWebHistoryController.init()` засевает историю только если `history.state` пуст, а `state` переживает перезагрузку — значит после F5 сева не происходит и адресная строка остаётся на своих N записях. `buildInitialStack` при этом строил стек из одного `window.location.pathname`: `[JobList, JobDetail]`, глубина 2. Дальше расхождение перестаёт быть косметическим, потому что переход в раздел — это `replaceAll`, а его контроллер зеркалит **относительным** `history.go(новаяГлубина − стараяГлубина)`. При стеке 2 и истории 5 «Jobs» превращается в `go(-1)`: браузер отматывает ровно одну запись, а `onPopState` затем подтягивает стек под неё — уезжает не только URL, но и экран.

  Починка — строить начальный стек из `webHistoryController.historyPaths` (Decompose документирует это свойство ровно для перезагрузки), с откатом на прежнее поведение, если сохранённый список расходится с адресной строкой или содержит дубли. Глубины снова равны, и `replaceAll` считает верную дельту: в прогоне после правки клик по Jobs даёт `go(-6)` при истории из шести записей вместо прежнего `go(-1)`. Побочный эффект — после F5 создаются компоненты всех восстановленных экранов, но ровно столько же живёт и при обычной прогулке по цепочке без перезагрузки: `ChildStack` держит неактивных детей созданными.

  Проверено на стенде восемью сценариями (F5 в середине цепочки, две F5 подряд, F5 + продолжение прогулки, F5 + переход в Upcoming, прогулка с возвратом + F5, Back с карточки, браузерный Back, Back после F5) — все ведут на список; регрессия по 0.8.3 (21 проверка) тоже зелёная.

- **2026-08-10** — **Процедура апгрейда RabbitMQ на Windows** (0.8.6; ops). В `docs/ops/` легли пофазный PowerShell-скрипт `Upgrade-RabbitMQ.ps1` (Preflight · Backup · Flags · Stop · Install · Verify) и чеклист `rabbitmq-upgrade-windows.md` под смену minor-ветки брокера, установленного нативным installer'ом. Повод — Risk-тикет на 4.2.0.0 (fixed version 4.2.6), цель 4.3.4. Кода релиз не касается: публикуемые модули, Docker-образ и Python-клиент в 0.8.6 те же, что в 0.8.5, бампнут только номер. К брокеру из `docker/infra/docker-compose.yml` процедура не относится — там версия меняется тегом образа.

  Три вещи, ради которых это записано отдельно, а не осталось в переписке. **(1) Feature flags включаются на старом узле до остановки.** 4.3 читает метаданные только тех узлов, у которых включены все stable-флаги предыдущей ветки, поэтому пропущенный `enable_feature_flag all` оставляет брокер, который не поднимется на собственном каталоге данных — а обнаруживается это уже после сноса старой версии, когда откатываться дороже всего. **(2) Каталог данных ищется через `RABBITMQ_BASE` → профиль LocalSystem → профиль пользователя.** Служба по умолчанию крутится под LocalSystem, и её `%APPDATA%` лежит в `C:\Windows\System32\config\systemprofile\AppData\Roaming\RabbitMQ`; бэкап пользовательского `%APPDATA%\RabbitMQ` в этом случае копирует пустоту, и это выясняется только при попытке восстановиться. **(3) Дистрибутив старой версии кладётся рядом с бэкапом заранее** — откат упирается не в данные, а в отсутствие installer'а, которого на странице релизов уже не найти первым.

  Отдельный пункт фазы Verify — плагин `rabbitmq_delayed_message_exchange`, на котором держатся отложенный запуск и retry-backoff (11.4, `docs/INTEGRATION.md`). Он внешний, и его `.ez` собирается под конкретную ветку брокера: после смены minor-версии его переустанавливают новой сборкой, а не просто включают заново.

- **2026-08-10** — **Зависимости выровнены по CsTradeService, и это вскрыло две поломки, которые CI не мог поймать** (0.8.7). Потребитель ушёл вперёд по общему набору библиотек, и теперь обе стороны резолвят по одной копии каждой: **Kotlin 2.3.21 → 2.4.10**, ktor 3.5.1, koin 4.2.2 (+ compiler plugin 1.0.1), exposed 1.3.1, postgresql 42.7.13, hikari 7.1.0, flyway 12.10.0, logback 1.5.38, mockk 1.14.11. Отдельно стоит отметить micrometer `1.17.0-RC1 → 1.17.0`: в публикуемых артефактах вплоть до 0.8.6 включительно ездил release candidate.

  **Kotlin и wrappers — одно изменение, а не два.** Релиз `kotlin-wrappers` собран одной конкретной версией компилятора, и его klib нечитаем более старым; `2026.6.1` был потолком, пока мы сидели на 2.3.21. Ушли на `2026.8.0`, а поскольку новые wrappers тянут другой набор npm-пакетов, `kotlin-js-store/yarn.lock` перегенерирован через `kotlinUpgradeYarnLock` (без этого `:kotlinStoreYarnLock` валит сборку). Главным риском был `:scheduler-compiler-plugin`: он живёт на самых подвижных IR API — `referenceFunctions` (soft-deprecated ещё в 2.3), позиционные конструкторы `IrCallImpl`/`IrVarargImpl`/`IrConstImpl`, модель `IrParameterKind` + `call.arguments[param]`. Ни одной правки не потребовалось. Доказательство не в том, что модуль компилируется, а в `SchedulerLambdaCaptureTest`: неперезаписанный `enqueueLambda` бросает из дефолтного тела, поэтому зелёный тест сам по себе означает, что K2-плагин продолжает переписывать вызовы. Известное предупреждение, не ошибка: Koin compiler plugin 1.0.1 протестирован на 2.3.20 и 2.4.0 и на 2.4.10 идёт через адаптер 2.4.0 — та же связка уже работает в CsTradeService.

  **`CancellationPropagationIntegrationTest` противоречил спецификации.** Тест утверждал, что при отмене родителя IGNORE-ребёнок остаётся в `AWAITING_DEPS` («operator opt-out»). 7.4 и 22.8 говорят обратное — «IGNORE → как при SUCCEEDED (decrement pending_deps)», и kdoc `OnFailure.IGNORE` с ними согласен: отказ от распространения сбоя означает, что ветка продолжает работу, а не что она зависает навсегда. Код соответствует спецификации с `b978341`, добавившего `promoteIgnoreSuccessors` в `DefaultScheduler.cancel` (каскад пропускает IGNORE-рёбра, потому что умеет только отменять, а промоутер их декрементит) — но тест тогда не обновили. Теперь он проверяет предписанное: C промотирован в ENQUEUED, `pending_deps` доведён до нуля, `cancel_requested_by` остался null. Добавлена проверка, которой не было: промоушен обязан записать ровно одну outbox-строку — состояния мало, ENQUEUED-джоба, которую никто не публикует, это тот же зависон в более незаметной форме.

  Остаётся открытым продуктовый вопрос: `promoteIgnoreSuccessors` применяет правило ко **всем** IGNORE-рёбрам, хотя комментарий у места вызова целится в `enqueueOnce`-успешника с политикой ENQUEUE_AFTER/REPLACE. По данным эти случаи неразличимы — обычный `enqueueAfter(onParentFailure = IGNORE)` создаёт ровно такое же ребро, — так что сузить поведение можно только новым признаком в схеме. Практический эффект: отмена родителя немедленно запускает его IGNORE-детей.

  **Обе поломки прожили незамеченными по одной причине: ни один CI-job не компилировал JVM-тесты.** `docker.yml` собирает только `:standalone-runner:shadowJar`, `python-client.yml` трогает Python — и `:app:compileTestKotlin` не компилировался со времён того же `b978341`, добавившего `policy: ConcurrencyPolicy` в `Scheduler.enqueueOnce` и не обновившего `RecordingScheduler`. Новый `jvm-tests.yml` закрывает дыру двумя стадиями: `classes testClasses` (≈минута, ловит поломку компиляции до запуска контейнеров), затем сами наборы. RabbitMQ поднимается service-контейнером — `SchedulerAutoConfigurationTest` единственный набор без собственного Rabbit-фолбэка, `RabbitJobTransport` открывает соединение сразу при сборке Koin-графа, и без живого брокера Spring-контекст не стартует; образ обязан быть с delayed-message-exchange. Postgres и MinIO осознанно оставлены на Testcontainers: per-class контейнеры изолируют наборы друг от друга так, как одна общая инстанция не может, а MinIO сервисом не поднять вовсе — образу нужен аргумент `server /data`, которого service-контейнеры не позволяют задать.
