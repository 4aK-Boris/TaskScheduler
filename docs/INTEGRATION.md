# Интеграция TaskScheduler в основной проект (Ktor + Koin)

Гайд для случая: у тебя есть основное приложение на **Ktor + Koin**, есть **Grafana**,
есть контейнер с **RabbitMQ**, аутентификация через **Keycloak**. Нужно встроить
TaskScheduler как библиотеку фоновых задач.

> Все примеры — рабочие сигнатуры из текущего кода (`core/backend`, `engine-worker`,
> `storage-postgres`, `transport-rabbit`), не псевдокод. Канонический минимальный
> пример потребителя — модуль `:app` (`app/src/main/kotlin/.../DemoApp.kt`).

---

## Оглавление
1. [Архитектура развёртывания (важно прочитать первым)](#1-архитектура)
2. [Как подключить модули (publishing сейчас НЕ настроен)](#2-зависимости)
3. [Инфраструктура: Postgres + твой RabbitMQ (нужен плагин!)](#3-инфраструктура)
4. [Запуск `scheduler-infra` (владелец схемы + dashboard + петли)](#4-scheduler-infra)
5. [Встраивание воркера в Ktor + Koin](#5-воркер)
6. [Написание handler'ов](#6-handlers)
7. [Постановка задач из бизнес-кода](#7-enqueue)
8. [Метрики → Prometheus → Grafana](#8-метрики)
9. [Dashboard + Keycloak](#9-keycloak)
10. [Дашборд: навигация и кнопка «назад» в браузере](#10-dashboard-spa)
11. [Чеклист и типичные грабли](#11-чеклист)

---

## 1. Архитектура

TaskScheduler разворачивается **двумя процессами** (DESIGN §14):

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│  scheduler-infra         │         │  ТВОЁ Ktor + Koin приложение  │
│  (отдельный контейнер)   │         │  (воркер-сторона)             │
│                          │         │                               │
│  • владеет схемой (Flyway)│        │  • @JobType handler'ы          │
│  • outbox publisher       │  PG +  │  • WorkerPool (исполняет джобы)│
│  • recurring / cron       │ Rabbit │  • Scheduler.enqueue(...)      │
│  • retention / safety-net │◄──────►│  • runMigrations = false       │
│  • leader election        │        │    (fail-fast по схеме)        │
│  • dashboard :8080 (UI+WS)│        │                               │
│  • /metrics, /health      │        │                               │
└─────────────────────────┘         └──────────────────────────────┘
            │                                       │
            └───────────► PostgreSQL ◄──────────────┘
            └───────────► RabbitMQ   ◄──────────────┘
```

**Почему split, а не всё-в-одном:**
- **Миграции владеет ровно один процесс** — `scheduler-infra` (`runMigrations = true`).
  Твоё приложение стартует с `runMigrations = false` и делает **fail-fast**, если в БД
  не накатаны миграции, которые встроены в его версию библиотеки (DESIGN §14.4). Это
  спасает от «column does not exist» через три дня после деплоя.
- **Фоновые петли (outbox/recurring/retention) и leader election — инфраструктурные.**
  Их не нужно гонять в каждом инстансе твоего приложения; они живут в `scheduler-infra`,
  где leader election гарантирует, что при N репликах тяжёлые петли крутит только одна.
- Твоё приложение делает только две вещи: **исполняет** джобы (`WorkerPool`) и **ставит**
  их (`Scheduler`).

> Технически можно встроить и `engine-infra` + `dashboard-server` прямо в своё приложение
> (см. `standalone-runner/Application.kt` — он именно так и собран). Но рекомендуемый и
> протестированный путь — отдельный `scheduler-infra`.

---

## 2. Зависимости

Библиотека публикуется в **mavenLocal** (`~/.m2`) под группой `cs.trade.scheduler`,
версия `0.1.0-SNAPSHOT`. artifactId = путь модуля через дефис (`core-backend`,
`engine-worker`, …). Настроено в `buildSrc/src/main/kotlin/buildsrc/convention/publish.gradle.kts`.

### Шаг 1 — опубликовать в mavenLocal (в репозитории TaskScheduler)
```bash
./gradlew publishToMavenLocal
```
Кладёт 8 библиотечных модулей в `~/.m2/repository/cs/trade/scheduler/`. Повторяй после
правок в библиотеке — версия SNAPSHOT, Gradle подхватит свежую без `--refresh-dependencies`.

### Шаг 2 — подключить в основном проекте
Добавь `mavenLocal()` в репозитории (`settings.gradle.kts`):
```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
```
В `build.gradle.kts`:
```kotlin
dependencies {
    implementation("cs.trade.scheduler:core-backend:0.1.0-SNAPSHOT")
    implementation("cs.trade.scheduler:storage-postgres:0.1.0-SNAPSHOT")
    implementation("cs.trade.scheduler:transport-rabbit:0.1.0-SNAPSHOT")
    implementation("cs.trade.scheduler:engine-worker:0.1.0-SNAPSHOT")
    // core-shared подтянется транзитивно (KMP — JVM-вариант резолвится по Gradle-метаданным)
}
```

> **Альтернатива — composite build** (без публикации, если проекты лежат рядом): в
> `settings.gradle.kts` `includeBuild("../TaskScheduler")` + по модулю
> `dependencySubstitution { substitute(module("cs.trade.scheduler:engine-worker")).using(project(":engine-worker")) }`.
> Для отдельного проекта mavenLocal проще.

> **Прод**: замени mavenLocal на внутренний Maven (Nexus / GitHub Packages) — тот же
> `maven-publish`, допиши `publishing { repositories { maven { url = … } } }` в
> `publish.gradle.kts` и используй `publish` вместо `publishToMavenLocal`.

### Что именно нужно воркер-приложению
| Модуль | Зачем |
|--------|-------|
| `:core:shared` | enum'ы/DTO (приходит транзитивно) |
| `:core:backend` | `Scheduler`, `JobHandler`, `JobContext`, `EnqueueOptions`, retry-политики |
| `:storage-postgres` | Hikari/Exposed/Flyway, `DefaultScheduler`, репозитории |
| `:transport-rabbit` | RabbitMQ-транспорт |
| `:engine-worker` | `WorkerPool`, метрики воркера |

Плюс в `build.gradle.kts` нужен **Koin compiler plugin** и **kotlinx.serialization**:
```kotlin
plugins {
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.koinCompiler)   // компайл-тайм Koin Annotations (не KSP)
}
```

> `engine-infra`, `dashboard-server`, `archival-s3` воркер-приложению **не нужны** — они
> только в `scheduler-infra`.

---

## 3. Инфраструктура

### PostgreSQL
Обычный Postgres 16. Схему накатывает `scheduler-infra` (см. §4). Твоё приложение
схему **не трогает**.

### RabbitMQ — ⚠️ нужен плагин `rabbitmq_delayed_message_exchange`
Твой текущий контейнер с ванильным `rabbitmq` **не подойдёт**. Транспорт объявляет
exchange `jobs.dispatch` типа `x-delayed-message` — он нужен для `scheduleAt(...)`,
отложенных ретраев и backoff-задержек (DESIGN §11.2). Без плагина объявление топологии
упадёт на старте.

Замени образ на кастомный (`docker/rabbitmq/Dockerfile` в репозитории):
```dockerfile
FROM rabbitmq:3.13-management-alpine
ARG PLUGIN_VERSION=3.13.0
RUN apk add --no-cache curl && \
    curl -L -o /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez \
      https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v${PLUGIN_VERSION}/rabbitmq_delayed_message_exchange-${PLUGIN_VERSION}.ez && \
    apk del curl
RUN rabbitmq-plugins enable --offline rabbitmq_delayed_message_exchange
```
В своём compose:
```yaml
  rabbitmq:
    build: ./docker/rabbitmq      # вместо image: rabbitmq:3.13
    environment:
      RABBITMQ_DEFAULT_USER: scheduler
      RABBITMQ_DEFAULT_PASS: scheduler
    ports: ["5672:5672", "15672:15672"]
```
(Если RabbitMQ уже развёрнут отдельно — просто включи плагин на нём:
`rabbitmq-plugins enable rabbitmq_delayed_message_exchange`.)

Топологию (`jobs.dispatch`, DLX `jobs.dlx`, очереди `q.{name}` с `x-max-priority=10`)
библиотека объявляет сама при старте транспорта — руками ничего создавать не нужно.

---

## 4. scheduler-infra

Это готовый контейнер (`docker/infra/Dockerfile`, точка входа
`standalone-runner/Application.kt`). Конфигурируется через env (`RunnerConfig.fromEnv()`):

```yaml
  scheduler-infra:
    build: { context: ., dockerfile: docker/infra/Dockerfile }
    environment:
      POSTGRES_URL: jdbc:postgresql://postgres:5432/scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: scheduler
      RABBITMQ_PASSWORD: scheduler
      RUN_MIGRATIONS: "true"            # ← ТОЛЬКО здесь
      NODE_ID: infra-1
      DASHBOARD_AUTH_PASSWORD: admin12345   # см. §9 про Keycloak
      # ARCHIVE_S3_BUCKET: ...          # опционально: архив терминальных джоб в S3
    ports: ["8080:8080"]               # dashboard + /metrics + /health
    depends_on:
      postgres: { condition: service_healthy }
      rabbitmq: { condition: service_started }
```

Что поднимается внутри: outbox publisher, recurring scheduler, fast-forward, safety-net,
retention cleanup, leader election, PG LISTEN/NOTIFY event bus, dashboard (REST + WS + SPA),
`/metrics` (Prometheus), `/health` (k8s-проба, без auth).

---

## 5. Воркер

Собери `DataSource` и `ConnectionFactory`, заведи 4 Koin-модуля библиотеки в **свой**
`install(Koin)`, и подними `WorkerPool` в жизненном цикле Ktor.

```kotlin
import com.zaxxer.hikari.*
import com.rabbitmq.client.ConnectionFactory
import io.ktor.server.application.*
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.inject
import org.koin.logger.slf4jLogger
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import cs.trade.scheduler.core.backend.schedulerCoreModule
import cs.trade.scheduler.core.backend.handler.retry.ExponentialBackoff
import cs.trade.scheduler.storage.postgres.infrastructure.schedulerPostgresModule
import cs.trade.scheduler.transport.rabbit.infrastructure.schedulerRabbitModule
import cs.trade.scheduler.engine.worker.infrastructure.schedulerWorkerModule
import cs.trade.scheduler.engine.worker.infrastructure.WorkerPool

fun Application.configureScheduler() {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = System.getenv("POSTGRES_URL")
        username = System.getenv("POSTGRES_USER")
        password = System.getenv("POSTGRES_PASSWORD")
        // ОБЯЗАТЕЛЬНО: иначе INSERT в JSONB-колонки (payload_json/context_json) падает —
        // PgJDBC по умолчанию шлёт строки как VARCHAR.
        addDataSourceProperty("stringtype", "unspecified")
    })
    val rabbit = ConnectionFactory().apply {
        host = System.getenv("RABBITMQ_HOST")
        username = System.getenv("RABBITMQ_USER")
        password = System.getenv("RABBITMQ_PASSWORD")
        isAutomaticRecoveryEnabled = true
    }

    install(Koin) {
        slf4jLogger()
        modules(
            schedulerCoreModule {
                nodeId = "my-app-1"
                defaultRetryPolicy = ExponentialBackoff(maxAttempts = 3)
            },
            schedulerPostgresModule {
                dataSource = ds
                runMigrations = false              // ← инфра владеет схемой
                // failFastOnSchemaMismatch = true (по умолчанию) — упадём на старте,
                // если в БД нет миграций нашей версии. Это фича, не баг.
            },
            schedulerRabbitModule {
                connectionFactory = rabbit
                queues = listOf("default", "email")  // какие очереди консьюмить
                prefetchPerConsumer = 10
            },
            schedulerWorkerModule {
                nodeId = "my-app-1"
                lockDuration = 60.seconds
                queue("default", concurrency = 10)
                queue("email", concurrency = 20)
            },
            // твой модуль с handler'ами — см. §6
            MyHandlersModule().module,
        )
    }

    // WorkerPool.start() открывает консьюмеров Rabbit; .stop() ждёт in-flight джобы.
    val workerPool by inject<WorkerPool>()
    monitor.subscribe(ApplicationStarted) { runBlocking { workerPool.start() } }
    monitor.subscribe(ApplicationStopping) {
        runBlocking { workerPool.stop() }   // graceful: до shutdownTimeout (по умолч. 30с)
        runCatching { ds.close() }
    }
}
```

> Если у тебя **уже** есть `install(Koin)` — не делай второй, а добавь 4 модуля
> библиотеки в свой существующий `modules(...)`. `install(Koin)` из koin-ktor дёргает
> `stopKoin()/startKoin()`, так что два вызова конфликтуют.

`schedulerWorkerModule` богат на тюнинг (см. KDoc): `defaultConcurrency`, `nodeTags`
(таргетинг джоб на инстанс), `heartbeatInterval`, на каждую очередь — `prefetch`,
`AdaptivePrefetch` (авто-tuning prefetch по p95-латентности), `CircuitBreakerConfig`
(per-node размыкатель), отдельный `dispatcher`, `onSchemaDriftAlert` (хук на смену
схемы payload'а).

---

## 6. Handlers

Handler = класс, реализующий `JobHandler<T>`, помеченный двумя аннотациями. Payload —
`@Serializable data class`, реализующий маркер `Job`.

```kotlin
import kotlinx.serialization.Serializable
import cs.trade.scheduler.core.backend.handler.*
import cs.trade.scheduler.core.backend.handler.retry.ExponentialBackoff
import org.koin.core.annotation.Single

@Serializable
data class SendEmail(val userId: Long, val template: String) : Job

@Single(binds = [JobHandler::class])   // Koin регистрирует за супертипом JobHandler
@JobType(SendEmail::class)             // какой payload мы обрабатываем
class SendEmailHandler(
    private val mailer: Mailer,        // любые твои Koin-зависимости — DI работает
) : JobHandler<SendEmail> {

    override suspend fun execute(ctx: JobContext, job: SendEmail) {
        mailer.send(job.userId, job.template)
    }

    // --- всё ниже опционально (есть дефолты) ---
    override val retryPolicy: RetryPolicy? get() = ExponentialBackoff(maxAttempts = 5)
    override val defaultPriority: Int get() = 5          // 0..10
    override suspend fun onFinalFailure(ctx: JobContext, job: SendEmail, error: Throwable) {
        // вызывается один раз ПОСЛЕ финального FAILED (исчерпаны попытки)
    }
}
```

`JobContext` даёт: `jobId` (стабильный idempotency-ключ через все попытки), `attempt`
(1-based), `maxAttempts`, `queue`, `enqueuedAt`, `parentJobIds`, а также
`updateProgress(fraction, msg)` (троттлится до 1/сек) и `isCancellationRequested()`
(для кооперативной отмены долгих циклов — кинь `JobCancellationException`).

**Счётчиковый прогресс-бар (как в JobRunr).** Для типового «обработать N элементов» удобнее
`progressBar(total)`, чем считать долю руками. Каждый элемент помечается успешным или
неуспешным — бар сам выводит долю `(succeeded + failed) / total`, а дашборд рисует
двухцветную полосу (зелёный успех / красный неудача) со счётчиками `✓ X  ✗ Y  / N`:
```kotlin
override suspend fun execute(ctx: JobContext, job: BulkExport) {
    val items = load(job)
    val bar = ctx.progressBar(total = items.size.toLong())
    for (item in items) {
        try { process(item); bar.succeeded() }
        catch (e: Exception) { bar.failed() }
    }
}
```
Запись троттлится тем же лимитом 1/сек, что и `updateProgress`; финальный инкремент
(`succeeded + failed == total`) пишется в обход троттла, поэтому бар не застревает у 100%.
`succeeded()`/`failed()` принимают `count` (батч) и `msg` (подпись шага). Инкрементить можно
из нескольких корутин одновременно.

**Koin Annotations (compiler plugin, не KSP).** Чтобы handler'ы попали в граф, нужен
модуль со сканом пакета:
```kotlin
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module
@ComponentScan("com.mycompany.jobs")   // пакет с handler'ами
class MyHandlersModule
```
`WorkerPool` собирает их через `getAll<JobHandler<*>>()` — поэтому обязателен
`binds = [JobHandler::class]`. Если забыть `@Single`/`binds`, handler не зарегистрируется
и джоба этого типа уедет в ретраи как «no handler».

---

## 7. Enqueue

Инжектишь `Scheduler` куда угодно (роут, сервис) и ставишь задачи:

```kotlin
class UserService(private val scheduler: Scheduler) {
    suspend fun onSignup(userId: Long) {
        // 1) сразу
        scheduler.enqueue(SendEmail(userId, "welcome"))

        // 2) на конкретную очередь/приоритет/таймаут
        scheduler.enqueue(
            SendEmail(userId, "welcome"),
            EnqueueOptions(queue = "email", priority = 8, timeout = 30.seconds),
        )

        // 3) отложенно (нужен delayed-message plugin, см. §3)
        scheduler.scheduleAt(SendEmail(userId, "day3-tips"), at = Clock.System.now() + 3.days)

        // 4) producer-side дедуп по стабильному ключу
        scheduler.enqueueOnce("welcome:$userId", SendEmail(userId, "welcome"))

        // 5) цепочка (каждый шаг ждёт предыдущего, PROPAGATE_FAILURE)
        scheduler.chain(StepA(userId), StepB(userId), StepC(userId), priority = 7)

        // 6) барьер: джоба после нескольких родителей
        val a = scheduler.enqueue(StepA(userId)); val b = scheduler.enqueue(StepB(userId))
        scheduler.enqueueAfter(StepC(userId), waitFor = listOf(a, b))
    }
}

// recurring/cron (обычно регистрируется один раз на старте).
// cron — 5 полей (UNIX, мин. гранулярность = минута) ИЛИ 6 полей с секундами
// ("s m h dom mon dow", напр. "*/10 * * * * *" = каждые 10 сек; DoW как в UNIX).
// Для sub-minute расписаний держи recurringPollInterval ≤ периода (дефолт 5с).
scheduler.recurring(RecurringDefinition(
    id = "nightly-report", cron = "0 3 * * *", job = NightlyReport(), timezone = "Europe/Moscow",
))
```

`EnqueueOptions`: `queue`, `priority` (0..10), `timeout`, `maxAttempts`, `retryPolicy`,
`targetNode` / `targetTag` / `targetQualifier` (роутинг на конкретный инстанс/группу),
`captureContext` (MDC + OTel-трейс, по умолч. `true`), `onParentFailure`,
`inheritPriorityFromParents`.

Операторские действия (их же дёргает dashboard): `cancel(jobId, by)`,
`retry(jobId, by, mode)` (`FRESH_BUDGET` / `ONCE` — кнопки «Retry» и «Retry +1»),
`delete(jobId, by)`, `reroute(jobId, targetNode, targetTag, by)`.

---

## 8. Метрики

Метрики на **двух сторонах**, обе через Micrometer → Prometheus. У тебя есть Grafana —
импортни готовый дашборд `docs/grafana-dashboard.json` (Dashboards → Import).

### 8.1. scheduler-infra (готово из коробки)
Контейнер уже отдаёт на `:8080/metrics`:
| Метрика (Prometheus) | Смысл |
|----------------------|-------|
| `scheduler_jobs_by_state{state=...}` | кол-во джоб по состояниям |
| `scheduler_outbox_unpublished` | необработанный backlog outbox |
| `scheduler_outbox_lag_seconds` | возраст самой старой неопубликованной строки |
| `scheduler_workers_total` / `scheduler_workers_alive` | живость воркеров |
| `scheduler_idempotency_dedup_total{action=...}` | подавленные дубли |

### 8.2. Твоё воркер-приложение (opt-in)
Метрики исполнения джоб (гистограмма латентности, in-flight, ретраи, circuit breaker)
живут там, где крутится `WorkerPool` — т.е. у тебя. По умолчанию `JobMetrics.Noop`
(нулевой оверхед). Чтобы включить — добавь `MeterRegistry` и переопредели биндинги
**после** `schedulerWorkerModule` (Koin last-wins):

```kotlin
import io.micrometer.prometheusmetrics.*
import io.micrometer.core.instrument.MeterRegistry
import cs.trade.scheduler.engine.worker.infrastructure.metrics.*

val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

// в modules(...), ПОСЛЕ schedulerWorkerModule:
module {
    single<MeterRegistry> { prometheus }
    single<JobMetrics> { MicrometerJobMetrics(get()) }                 // гистограмма execute
    single { WorkerMetricsBinder(get(), get(), get(), get()).also { it.bind() } }
}
// заставь WorkerMetricsBinder инстанцироваться (иначе gauge'и не зарегистрируются):
koin.get<WorkerMetricsBinder>()
```
Экспонируй `/metrics` в своём Ktor (плагин `MicrometerMetrics` с тем же `prometheus`,
либо простой роут `call.respondText(prometheus.scrape())`).

Метрики воркер-стороны:
| Метрика | Тэги | Смысл |
|---------|------|-------|
| `scheduler_job_execution_seconds` (`_count`/`_sum`/`_bucket`) | `queue`, `payload_type`, `outcome` | латентность/исход `execute` (p50/p95/p99 через `histogram_quantile`) |
| `scheduler_retry_total` | `queue`, `payload_type` | запланированные ретраи |
| `scheduler_worker_in_flight` | `queue`, `node` | взято в работу, не финализировано |
| `scheduler_circuit_breaker_state` | `queue`, `node` | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |

### 8.3. Prometheus scrape
Скрейпь **оба** таргета:
```yaml
scrape_configs:
  - job_name: scheduler-infra
    static_configs: [{ targets: ["scheduler-infra:8080"] }]
  - job_name: my-app
    static_configs: [{ targets: ["my-app:<твой-порт>"] }]
```

---

## 9. Keycloak

Единственная auth-поверхность библиотеки — **dashboard** (`:8080`, REST + WS + SPA).
Воркеры/движок auth не имеют. Auth-DSL дашборда (`schedulerDashboardModule { auth { ... } }`)
поддерживает `none()`, `basic { }` и **`custom("providerName") { actorExtractor = ... }`**.
Встроенный HMAC256-JWT prebuilt-образа Keycloak-токены не принимает (Keycloak — RS256/JWKS),
**но в рантайме есть отдельный OIDC/JWKS-провайдер** — Keycloak заводится через env, без
шлюза и без встраивания.

Варианты (от простого к гибкому):

- **(рекомендую для prebuilt-образа) нативный OIDC/JWKS через env.** На `scheduler-infra`:
  ```yaml
  DASHBOARD_OIDC_ISSUER: https://kc.example.com/realms/myrealm   # ожидаемый iss
  DASHBOARD_OIDC_AUDIENCE: my-dashboard-client                   # ожидаемый aud (client id), опц.
  # DASHBOARD_OIDC_JWKS_URL: ...  # опц.; по умолчанию <issuer>/protocol/openid-connect/certs
  ```
  Рантайм валидирует RS256-токены по JWKS Keycloak (ключи кэшируются), `sub` → аудит.
  Работает рядом с Basic/HMAC-JWT — проходит запрос с любым из настроенных методов. Для
  REST и сервис-в-сервис — достаточно; для браузерного WS см. нюанс ниже.

- **`oauth2-proxy` / Keycloak-gatekeeper перед `:8080`.** Шлюз терминирует OIDC; дашборд с
  `none()` или доверяет шлюзу. Главный плюс — cookie-сессия покрывает **WebSocket** и даёт
  полноценный SSO-вход в саму UI в браузере (redirect/PKCE на стороне шлюза).

- **Встроить `dashboard-server` в свой Ktor** и поставить свой провайдер через `custom`:
  ```kotlin
  install(Authentication) {
      jwt("keycloak") {
          verifier(JwkProviderBuilder(URI.create("https://kc/realms/<realm>/protocol/openid-connect/certs").toURL()).build(),
                   issuer = "https://kc/realms/<realm>") { withAudience("<client-id>") }
          validate { JWTPrincipal(it.payload) }
      }
  }
  // schedulerDashboardModule { auth { custom("keycloak") {
  //     actorExtractor = { it.principal<JWTPrincipal>()?.subject ?: "anonymous" } } } }
  ```
  Роутинг дашборда монтируется внутри `authenticate("keycloak")`. `actorExtractor` достаёт
  `sub`/`preferred_username` для аудита (`MANUAL_*` события).

**Нюансы:** браузерный WebSocket-файрхоуз (`/api/ws/events`) под чистым bearer не
аутентифицируется (нельзя выставить заголовок из JS) — поэтому путь со шлюзом + cookie
самый гладкий для всей UI. Сам wasm-SPA не содержит OIDC-login-флоу (redirect/PKCE) —
SSO-вход в UI в браузере = либо шлюз, либо доп. фронтенд-работа. Защитить **API** дашборда
Keycloak'ом — поддерживается уже сейчас. Подробности — в обсуждении выше по этому вопросу.

---

<a id="10-dashboard-spa"></a>
## 10. Дашборд: навигация и кнопка «назад» в браузере

Дашборд (`:dashboard-web`) — Compose/Wasm **SPA** с навигацией через History API браузера
(Decompose `WebHistoryController`):
- работают кнопки **назад/вперёд** браузера;
- адрес отражает экран через **query-строку**: `/?jobs`, `/?jobs/{id}`, `/?recurring`, `/?workers`,
  `/?types`, `/?type-stats`, `/?stats`;
- по такой ссылке можно зайти напрямую / перезагрузить (F5) — экран восстановится (для
  `/?jobs/{id}` поднимается стек `[список, карточка]`, чтобы «назад» вёл к списку).

Ключевой момент: используется **query-роутинг** (экран в `?…`), а НЕ чистые пути (`/jobs/{id}`).
Поэтому путь всегда остаётся `/`, и **со стороны сервера настраивать ничего не нужно** — твоя текущая
раздача статики (index.html + `*.wasm`/`*.js`/`skiko.*` на `/`) работает как есть: reload и шаринг
ссылок не дают 404, ассеты грузятся с корня. (Чистые пути потребовали бы SPA-fallback + абсолютный
`publicPath`, и были бы хрупки для wasm — поэтому выбран query-вариант.)

`?mock` (dev-режим с фейковыми данными) тоже живёт в query; при навигации он вытесняется текущим
экраном — это ожидаемо и только для разработки.

## 11. Чеклист

Перед первым запуском:
- [ ] RabbitMQ с плагином `rabbitmq_delayed_message_exchange` (иначе старт транспорта падает).
- [ ] `addDataSourceProperty("stringtype", "unspecified")` на Hikari (иначе INSERT в JSONB падает).
- [ ] `runMigrations = true` **только** в `scheduler-infra`; в твоём приложении — `false`.
- [ ] Сначала поднять `scheduler-infra` (накатит схему), потом — приложение (иначе fail-fast по схеме — это ожидаемо).
- [ ] Каждый payload — `@Serializable` и реализует `Job`.
- [ ] Каждый handler — `@Single(binds = [JobHandler::class])` + `@JobType(...)`, и пакет попадает в `@ComponentScan`.
- [ ] Имена очередей в `schedulerRabbitModule.queues` ⊇ имена в `schedulerWorkerModule.queue(...)`, которые хочешь консьюмить.
- [ ] `WorkerPool.start()`/`.stop()` повешены на жизненный цикл Ktor.
- [ ] (если нужны метрики воркера) `MeterRegistry` + override `JobMetrics` + `WorkerMetricsBinder.bind()` + `/metrics` роут.
- [ ] (дашборд) ничего настраивать не нужно — навигация query-based, путь всегда `/` (см. §10).

Типичные грабли:
- **«no handler for type X»** в ретраях → handler не зарегистрирован (нет `@Single`/`binds`,
  или пакет не в `@ComponentScan`).
- **Старт падает с «Schema mismatch»** → приложение свежее инфры; накати инфру на эту БД
  (это защита, а не баг; обход — `failFastOnSchemaMismatch = false`, не для прода).
- **Джобы ставятся, но не исполняются** → проверь, что `queues` в rabbit-модуле включает
  очередь, и что воркер реально `start()`-ит (лог `WorkerPool ...`).
- **`enqueueLambda { }` кидает на рантайме** → нужен `scheduler-compiler-plugin` в plugins;
  без него используй типизированный `enqueue(Receiver::method, args...)`.

---

*Связанные документы: `DESIGN.md` (§8 API, §12 Koin, §14 деплой, §11 Rabbit-топология),*
*`docs/grafana-dashboard.json` (готовый дашборд), модуль `:app` (рабочий пример воркера).*
