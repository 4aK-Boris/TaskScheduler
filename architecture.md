# Architecture

Документ фиксирует модульную структуру проекта и требования к каждому модулю.
Источник правды о том, **что куда писать** и **как именовать**.

## Модули проекта

Все модули живут в `src/main/kotlin/cs/trade/<module>/`.

| Модуль | Зона ответственности |
|---|---|
| **`core`** | Кросс-модульные примитивы: HTTP/WS client, Ktor plugins, БД-обёртки, time/clock, метрики, исключения, базовые use case'ы (`BaseUseCase`, `runCatchingWithLogging`, `parallel{N}Fun`), сериализация. Не содержит бизнес-логики. Все остальные модули зависят от `core`, обратной зависимости нет. |
| **`di`** | Koin-конфигурация и точки сборки графа. KSP генерирует модули, тут только верхнеуровневая склейка + ручные модули. |
| **`feature`** | Feature flags. Абстракция `FeatureFlagService` с двумя реализациями: `UnleashFeatureFlagService` (prod) и `LocalFeatureFlagService` (dev/test). Переключение через `FEATURE_FLAGS_MODE`. |
| **`system`** | Системные/админские эндпоинты, health-чеки, общие операционные API. |
| **`analysis`** | Анализ листингов и форкастинг цен (P1 evaluation). `ArbitrageEvaluation`, `ItemSellContext`, прогнозы через CatBoost (`trade-forecast` HTTP-сервис), метаданные предметов (skins/stickers/containers/misc), кэши Caffeine для type/metadata/cross-section/order-book, `ItemsListingsSource` как шина листингов. |
| **`marketplace`** | Интеграции с торговыми площадками. Под-модули по маркетплейсам — каждый сам себе модуль со своими тремя слоями: |
| `marketplace/market_csgo` | MarketCSGO: WS-канал листингов (~100 msg/sec), GraphQL/HTTP API, ордер-буки, sales history, buy-orders. |
| `marketplace/lisskins` | LisSkins: Centrifugo WS, листинги, покупки. |
| `marketplace/tradeonmarket` | TradeOnMarket: аккаунты, баланс, инвентарь, нотификации, листинги, трейды. |
| **`steam`** | Steam-аккаунты, токены (refresh/access), мобильное приложение (`SteamMobileRepository` — WIP), приём/отправка trade offer'ов, mobile confirmations. |
| **`trading`** | Графовая модель торговли (см. `trading-graph-architecture.md`): `Graph`, `GraphNode`, `PipelineItem`, `Offer`, `AccountPool`, `BlockedItem`, lifecycle переходы, marketplace-сервисы (`MarketplaceBuyService`, `SellService`, `RepricingService`, `CashoutService`), оркестрация (`AdvanceGraphNodeUseCase`). |
| **`pulse`** | Источник данных Pulse — агрегатор цен/листингов сторонних маркетов, периодический pull через `UpdatePulseMarketItemsFromAllMarketsTask`. |
| **`scheduler`** | JobRunr-обвязка и определения тасков (см. `jobrunr.md`). Не содержит бизнес-логики — таски делегируют в `<module>.domain.usecases`. |

Файлы в корне `cs/trade/`: `Application.kt` (Ktor entrypoint), `Routing.kt` (склейка роутов модулей). `Test.kt` в этом списке быть не должен — это временный мусор.

## Требования к структуре модуля

Каждый функциональный модуль (`analysis`, `feature`, `marketplace/*`, `pulse`, `steam`, `system`, `trading`) разбит максимум на **три слоя**:

```
cs.trade.<module>/
├── api/              — внешний HTTP/WS-контракт модуля
├── domain/           — модели, репозитории-контракты, use case'ы (бизнес-логика)
└── infrastructure/   — реализации репозиториев, мапперы, адаптеры к внешним системам
```

**Слой добавляется только если он нужен**:
- Если модуль не выставляет наружу HTTP/WS — слоя `api` нет (примеры: `feature`, `pulse`, `marketplace/lisskins`, `marketplace/tradeonmarket`).
- Если модуль чисто доменный без БД/внешних адаптеров — слоя `infrastructure` нет (на практике сейчас такого модуля нет).
- Слой `domain` есть всегда.

Исключения из правила:
- **`core`** — не функциональный модуль, у него собственная подструктура (`network/`, `database/`, `time/`, `usecases/`, `metrics/`, `serialization/` и т.п.). Слои не применяются.
- **`di`** — конфиг-only, без слоёв.
- **`scheduler`** — таски сгруппированы по доменному источнику (`tasks/analysis/`, `tasks/marketplace/...`, `tasks/trading/`, `tasks/steam/`, `tasks/pulse/`, `tasks/cache/`), слои не применяются — это адаптер JobRunr к use case'ам из других модулей.

## Слой `infrastructure`

Реализации репозиториев и адаптеры к внешним системам. Реальный пример всех элементов — `cs.trade.steam.infrastructure/`.

```
<module>/infrastructure/
├── network/        — интерфейсы сетевых клиентов + Impl
├── dto/            — модели данных запросов/ответов
├── mappers/        — DTO ↔ domain model (только для сложных преобразований)
├── repositories/   — реализации domain-репозиториев (*RepositoryImpl)
├── cache/          — реализации Caffeine-кешей из domain
├── tables/         — Exposed `Table` object'ы для PostgreSQL
└── core/           — служебные мелочи модуля, если есть
```

Подпапки добавляются только когда нужны (например, у `steam/infrastructure` нет `cache/` — кешей в этом модуле нет).

### Network

- **Каждый Network — это интерфейс + реализация.** Имена: `XxxNetwork` (interface) + `XxxNetworkImpl`. Пример: `SteamAccountNetwork` + `SteamAccountNetworkImpl`.
- Network инкапсулирует работу с одним внешним сервисом/сегментом сервиса. Если у Steam-API большие части (auth / account / inventory / mobile) — это отдельные Network'и, не один монолитный.
- Network **использует HTTP/WS-клиенты из `core`** (общий `HttpClient`/`WebSocketClient` с настроенными плагинами), не создаёт свои.
- **Network вызывается ТОЛЬКО из репозиториев** того же модуля. Никаких прямых вызовов Network из use case'ов, из реализаций кешей, из мапперов и т.п.

### DTO

- Data Transfer Object — модель, которая летит в теле HTTP/WS-запроса или приходит в ответе.
- Именование: `XxxRequestDTO` / `XxxResponseDTO` для request/response пар, или `XxxDTO` для остального. Пример: `BeginAuthSessionViaCredentialsRequestDTO`, `ConfirmationDTO`.
- Группируются в `dto/` подпапками по доменной части API (`dto/auth/`, `dto/auth/openid/`, …). Общие DTO модуля — прямо в `dto/`.
- При наличии `@Serializable` **каждое поле обязано иметь `@SerialName("имя в JSON")`** — даже если оно совпадает с именем Kotlin-поля. Это страхует от рефакторинга, который сломает сериализацию.

### Cache

- В `infrastructure/cache/` лежат **реализации** кеш-абстракций, объявленных в `domain/cache/` как интерфейсы (типовое имя — `XxxCacheStore`).
- Кеш может хранить либо domain-модель, либо DTO — выбор по ситуации. **DTO в кеше уместен, когда нужно хранить не все поля domain-модели** (например, сырой ответ маркета, из которого реально нужен только один атрибут).

### Repository (Impl)

- В `infrastructure/repositories/` — реализации domain-репозиториев. Имя: `XxxRepositoryImpl`.
- **Один репозиторий = одна смысловая группа методов.** Пример из `steam`: отдельные репозитории под аккаунты (`SteamAccountRepositoryImpl`), под токены (`SteamAccountTokensRepositoryImpl`), под активный аккаунт (`ActiveSteamAccountRepositoryImpl`), под auth (`SteamAuthRepositoryImpl`), под mobile (`SteamMobileRepositoryImpl`) — не один `SteamRepositoryImpl` на всё.
- **Если репозиторий разросся** — разбить по функционалу на два, разделив методы по тому, *что* они делают.
- Репозиторий — единственное место, откуда можно вызывать Network этого модуля.

### Mapper

- Mapper `XxxMapper` преобразует `DTO ↔ domain model` (одна сторона или обе).
- **Маппер нужен, только если преобразование нетривиально**: вложенные классы, нетривиальная логика поля, специальные правила для null'ов и т.п.
- Для простого «переложить 3 поля» — **не делать маппер**, написать функцию-конвертер прямо внутри репозитория.

### Tables (Exposed)

- Object-определения схемы PostgreSQL для Exposed ORM лежат в `infrastructure/tables/`. Имя: `XxxTable` (object, не class). Пример: `SteamAccountsTable`.
- Шаблон создания — через skill `infra-table`. Миграции — через `flyway-migration`.

## Слой `domain`

Чистая бизнес-логика модуля: модели данных, контракты репозиториев и кешей, use case'ы, бизнес-сервисы. **Никаких внешних зависимостей** — Ktor, Exposed, Caffeine, HTTP-клиенты и т.п. живут в `infrastructure`/`api`. Реальный пример всех элементов — `cs.trade.steam.domain/`.

```
<module>/domain/
├── models/         — data классы (без @Serializable)
├── repositories/   — интерфейсы репозиториев
├── cache/          — интерфейсы CacheStore (опционально, только если есть кеши)
├── usecases/       — UseCase'ы (единицы бизнес-логики)
├── services/       — бизнес-сервисы (опционально: калькуляторы, генераторы, политики)
└── core/           — служебные мелочи модуля (опционально)
```

Группировать содержимое подпапками по подгруппам (`models/accounts/`, `models/auth/openid/`, `usecases/accounts/active/`, ...) когда сущностей много.

### Models

- `data class`, **без** `@Serializable` — сериализация это работа DTO в `infrastructure`/`api`.
- Все поля `val` (immutable).
- Типы — стандартные Kotlin; `kotlin.time.Instant` для timestamps, не `java.time.*`.
- Без бизнес-логики, без поведения — это плоские носители данных, с которыми работают use case'ы.

Пример:
```kotlin
package cs.trade.steam.domain.models.accounts

import kotlin.time.Instant

data class SteamAccount(
    val steamId: Long,
    val login: String,
    val password: String,
    val sharedSecret: String,
    val identitySecret: String,
    val revocationCode: String,
    val deviceId: String,
    val createdAt: Instant
)
```

### Repository interfaces

- Пакет: `domain/repositories[/group]/`. Имя: `XxxRepository`.
- **Одна смысловая группа методов = один интерфейс** (правило симметрично `infrastructure`). Пример из `steam`: `SteamAccountRepository`, `SteamAccountTokensRepository`, `ActiveSteamAccountRepository`, `SteamAuthRepository`, `SteamMobileRepository` — пять разных репозиториев под разные смысловые группы, не один монолитный `SteamRepository`.
- Если интерфейс разросся (>10 методов или явные подгруппы) — разбить на два.
- Никаких аннотаций — интерфейс не знает про DI. Аннотации только на `Impl` в `infrastructure`.

### Cache interfaces

- Пакет: `domain/cache/`. Имя: `XxxCacheStore`.
- Только описывают **что** кеш умеет (`getXxx`, `addXxx`, `invalidate*`). Сами кеши на Caffeine — в `infrastructure/cache/` (см. skill `infra-cache`).
- Loader-параметр сигнатуры выбирается по семантике: `() -> XxxModel` если предмет всегда есть, `() -> XxxModel?` если может отсутствовать. Это **сигнал** для реализации, как обрабатывать null.

### UseCases

- Наследуются от `BaseUseCase` (`cs.trade.core.usecases.BaseUseCase`).
- Один публичный метод: `operator fun invoke(...)`. Если есть IO или suspend-зависимости — `suspend operator fun invoke(...)`.
- Возврат **всегда** `Result<T>` или `Result<T?>`.
- Koin-аннотация: `@Singleton` (дефолт) или `@Factory` (для stateless без shared-state — реже).
- Размер: одна логическая операция. **Не пихать много бизнес-логики в один UseCase** — разделять.

#### Правило 1:1 «функция репозитория ↔ UseCase»

- **Каждая функция репозитория обёрнута в отдельный UseCase**, и эта функция вызывается **только из этого UseCase**. Другие UseCase, которым нужен этот метод репо, дёргают этот UseCase, а не репозиторий напрямую.
- Пример: `SteamAccountRepository` имеет 8 методов → в `usecases/accounts/` лежат 8 UseCase'ов (`UpsertSteamAccountUseCase`, `GetSteamAccountBySteamIdUseCase`, `DeleteSteamAccountBySteamIdUseCase`, …).
- **Исключение**: вспомогательные функции, нужные многим UseCase (например, получение access-token), допустимо вызывать из других UseCase напрямую без отдельной обёртки, чтобы не плодить тривиальный код.

#### Cache всегда через UseCase

- Cache **не вызывается из use case'ов из других модулей напрямую** — только через UseCase того модуля, которому кеш принадлежит.
- Типичный паттерн «кеш + источник» — **две** UseCase:
  - `GetXxxFromDatabaseUseCase` — берёт из БД через репозиторий
  - `GetXxxFromCacheUseCase` — берёт из кеша; при cache miss вызывает первый как loader
  Внешние потребители работают только с `GetXxxFromCacheUseCase`. Аналогично может быть `GetXxxFromNetworkUseCase` + кеш-обёртка.

#### Внутри `invoke`

- **Если UseCase просто делегирует одному или нескольким другим UseCase** — возвращать их `Result` напрямую через `.mapCatching {}`, `.fold {}`, `.flatMap {}` и т.п. **Без** `runCatchingWithLogging`, потому что вложенные UseCase уже логируют сами. Пример — `LoginAndSaveCookieUseCase` (склейка 4 других UseCase + `TimeProvider`/`SteamGuardGenerator`).
- **Если UseCase делает «свою» работу** (вызов репозитория, своя трансформация, бизнес-логика) — обернуть тело в `runCatchingWithLogging { ... }`, чтобы получить старт/финиш/elapsed-логи и `Result` на выходе.
- **Если «не нашли» — штатный случай** (новый/неизвестный предмет, отсутствие конфига и т.п.) — возвращать `Result<T?>` и **не бросать** `error(...)`. Иначе на каждый штатный промах будет ERROR-стектрейс в логе. Этот паттерн зафиксирован в `CLAUDE.md` → Logging.
  - Caller проверяет `null` и тихо скипает (`?: return@runCatching null`, `getOrNull()` + `filterNotNull()` в Flow, и т.п.).
  - Если «не нашли» = реальная ошибка (например, `upsert` обязан вернуть результат) — тогда `?: error("...")` уместен.

Пример простой обёртки одного метода репозитория:
```kotlin
@Factory
class UpsertSteamAccountUseCase(
    private val steamAccountRepository: SteamAccountRepository
) : BaseUseCase() {

    suspend operator fun invoke(steamAccount: SteamAccount): Result<UpsertSteamAccount> = runCatchingWithLogging {
        steamAccountRepository.upsertSteamAccount(steamAccount)
            ?: error("Error with upsert SteamAccount = $steamAccount")
    }
}
```

Пример композитного UseCase без `runCatchingWithLogging`:
```kotlin
@Singleton
class LoginAndSaveCookieUseCase(
    private val timeProvider: TimeProvider,
    private val steamGuardGenerator: SteamGuardGenerator,
    private val getSteamAccountCredentialsUseCase: GetSteamAccountCredentialsUseCase,
    private val getAccessAndRefreshTokensForSteamAccountUseCase: GetAccessAndRefreshTokensForSteamAccountUseCase,
    private val generateAndSaveSteamCookieUseCase: GenerateAndSaveSteamCookieUseCase,
    private val updateActiveSteamAccountUseCase: UpdateActiveSteamAccountUseCase
) : BaseUseCase() {

    suspend operator fun invoke(input: InsertActiveSteamAccountInput): Result<InsertActiveSteamAccountOutput> {
        return getSteamAccountCredentialsUseCase.invoke(input.steamId).mapCatching { (steamId, login, password, sharedSecret) ->
            val (accessToken, refreshToken) = getAccessAndRefreshTokensForSteamAccountUseCase
                .invoke(steamId, login, password, sharedSecret).getOrThrow()
            val sessionId = steamGuardGenerator.generateSessionId()
            // ... сборка ActiveSteamAccount, делегирование дальше ...
        }
    }
}
```

### Services (опционально)

- Бизнес-сервисы, которые не вписываются в форму UseCase: калькуляторы (`MarginBasedSellPriceCalculator`), политики/стратегии, генераторы (`SteamGuardGenerator`), валидаторы доменных правил.
- Если есть несколько реализаций одной абстракции — выделять интерфейс в `services/` + Impl в `infrastructure/`. Если одна — просто `class` под `@Singleton`.
- Папка `domain/core/` — для совсем мелочей модуля, которые трудно отнести к services/models (например, `SteamGuardGenerator`).

### Что НЕ делать

- Не вешать `@Serializable` на domain-модели — это инфраструктурное.
- Не зависеть из domain на `infrastructure`/`api` — стрелка зависимости только обратная.
- Не вызывать репозиторий из UseCase напрямую, если уже есть UseCase-обёртка над этим методом — использовать обёртку.
- Не вызывать Network/Cache/Repository из use case'ов **другого модуля** напрямую — только через публичный UseCase соседнего модуля.
- Не делать `error("…")` на штатные «не нашли» — возвращать `Result<T?>` (см. недавний фикс `GetItemTypeFromDatabaseUseCase`).
- Не оборачивать в `runCatchingWithLogging` то, что и так делегирует другим UseCase — двойное логирование.
- Не делать монолитные `SteamRepository` / `SteamUseCase` — разбивать по смысловым группам.
- Не выставлять из UseCase публично что-то кроме `operator fun invoke`.

## Слой `api`

Внешний HTTP-контракт модуля. Один путь запроса проходит через **6 шагов**: `extractor` → `validation` → `mapper.toModel` → `usecase.invoke` → `mapper.toDto` → ответ; описывается Swagger через `description`. Каркас этой склейки — хелперы `postHandle`/`getHandle`/`getHandleOutput`/`getListHandle`/`deleteHandle`/... из `cs.trade.core.ktor`. Реальный пример всех элементов — `cs.trade.steam.api/`.

```
<module>/api/
├── dto/             — Request/Response @Serializable data классы
├── validations/     — Konform-валидаторы (XxxValidation : BaseValidation)
├── descriptions/    — Swagger OpenAPI (XxxDescription : BaseDescription())
├── extractors/      — извлечение query/path параметров (XxxExtractor)
├── mappers/         — DTO ↔ Domain (XxxApiMapper) — обязательны
└── routes/          — Ktor routing файлы + корневой модульный роутинг
```

Каждому типу — отдельный skill: `api-dto`, `api-validation`, `api-description`, `api-extractor`, `api-mapper`, `api-swagger-route`.

### DTO

- `@Serializable` data классы в `api/dto/`. Один DTO на файл.
- Имена: `{Action}{Resource}Request` / `{Action}{Resource}Response`. Actions: `Insert`, `Update`, `Delete`, `Get`, `Upsert`.
- **БЕЗ `@SerialName`** — в нашем API имена полей в JSON соглашением **совпадают с Kotlin-полями** (camelCase). Кастомные имена не нужны, не загромождаем. Это отличие от `infra-dto`, где `@SerialName` обязателен на каждом поле, т.к. внешние API диктуют свои имена.
- ID-поля — **`String`** (в DTO API-слоя), конвертируются в `Long` уже в Mapper.
- Request имеет `companion object` с `example` и `emptyExample` для Swagger примеров. Response — без companion.

### Validation (Konform)

- `class XxxValidation : BaseValidation` (`BaseValidation` — **интерфейс** из `cs.trade.core.ktor.validation`), `@Singleton`.
- Каждое правило — `val validateXxx = Validation { ... }`. Можно валидировать целый DTO (`InsertSteamAccountRequest::login { minLength(1) ... }`) или одиночный тип (`validateSteamId = Validation { positiveLong() }`).
- **Разделение по смыслу** — как у репозиториев. Один валидатор покрывает одну смысловую группу ручек (`SteamAccountValidation`, `ActiveSteamAccountValidation`). Не один монолитный `SteamValidation` на весь модуль.
- Из `BaseValidation` доступен общий extension `positiveLong()` для строковых ID. Остальное — стандартные Konform constraints (`minLength`, `maxLength`, `pattern`, `minimum`/`maximum`, кастомные через `constrain { ... }`).

### Description (Swagger/OpenAPI)

- `class XxxDescription : BaseDescription()` (`BaseDescription` — **abstract class** из `cs.trade.core.ktor`), `@Singleton`.
- Один метод на endpoint: `fun {action}{Resource}Description(routeConfig: RouteConfig) { with(routeConfig) { tags(TAG); summary = ...; description = ...; operationId = ...; request { ... }; response { ... } } }`.
- В `response`:
  - `HttpStatusCode.OK to { body<Success<XxxResponse>>() }`
  - `HttpStatusCode.BadRequest to { body<Error>() }` — **обязательно для всех endpoint'ов**, чтобы Swagger показывал shape ошибки.
  - Обёртки `ApiResponse.Success<T>` / `ApiResponse.Error` — из `cs.trade.core.ktor.api`.
- В request:
  - POST/PUT — `body<XxxRequest> { example("Имя") { value = XxxRequest.example } }` (использует companion object DTO).
  - GET/DELETE — `queryParameter<String>(name = PARAM_NAME) { description = ...; required = true; example { value = ... } }`.
- `companion object` хранит **публичные** константы имён параметров (`STEAM_ID_PARAMETER_NAME`) — их импортирует `XxxExtractor` — и **приватные** константы тегов и примеров.

### Extractor

- `class XxxExtractor`, `@Singleton`. **Без наследования** (нет `BaseExtractor`), без зависимостей.
- Один метод на параметр: `fun extract{ParamName}(call: ApplicationCall): String`.
- Всегда возвращает `String`. Fallback `?: EMPTY_STRING` из `cs.trade.core.EMPTY_STRING` — не бросать исключения при отсутствии параметра, валидация поймает «пустую строку».
- Имена параметров **импортируются** из `XxxDescription.Companion.XXX_PARAMETER_NAME` — не хардкодим строки в двух местах.

### Mapper

- `class XxxApiMapper`, `@Singleton`. **Обязательны в api-слое** — в отличие от `infra-mapper`, которые делаются только для сложных преобразований. В api маппер есть всегда: API-DTO живут отдельной жизнью от domain-моделей (`String` ID ↔ `Long`, `createdAt` через `TimeProvider`, разные shape'ы), нельзя экономить на нём.
- Опционально инжектят `TimeProvider` (для `createdAt` на новых сущностях) или другие сервисы.
- Методы:
  - `fun toDto(model: DomainModel): XxxResponse` — Domain → Response DTO
  - `fun toModel(dto: XxxRequest): DomainModel` — Request DTO → Domain
  - Перегрузки `toDto` для разных пар response-моделей — обычная практика.
- **Разделение по смыслу** — как у репозиториев. `SteamAccountsApiMapper`, `ActiveSteamAccountsApiMapper` — отдельные мапперы под смысловые группы, не один монолитный.
- Только конверсия типов. Никакой бизнес-логики, никаких вызовов use case/repo.

### Routes — двухуровневая структура

**Корневой модульный routing** — `XxxRouting.kt`:
```kotlin
fun Routing.configureSteamRouting() {
    route("/api/steam") {
        configureSteamAccountRouting()
        configureActiveSteamAccountRouting()
    }
}
```
Задаёт префикс модуля (`/api/{module}`) и вызывает подгруппы.

**Подгрупповой routing** — `XxxYyyRouting.kt` (один файл на смысловую группу — симметрия с репозиториями):
```kotlin
fun Routing.configureSteamAccountRouting() {
    val description by inject<SteamAccountDescription>()
    val validation by inject<SteamAccountValidation>()
    val extractor by inject<SteamAccountExtractor>()
    val mapper by inject<SteamAccountsApiMapper>()
    val upsertUseCase by inject<UpsertSteamAccountUseCase>()
    // ...
    route(path = "/account") {
        postHandle(
            builder    = description::upsertSteamAccountDescription,
            validation = validation.validateUpsertSteamAccount,
            mapToModel = mapper::toModel,
            mapToDto   = mapper::toDto,
            processing = upsertUseCase::invoke,
        )
        route(path = "/by_steam_id") {
            getHandle(
                builder    = description::getSteamAccountBySteamIdDescription,
                validation = validation.validateSteamId,
                extractor  = extractor::extractSteamId,
                mapToModel = String::toLong,
                mapToDto   = mapper::toDto,
                processing = getBySteamIdUseCase::invoke,
            )
        }
    }
}
```

Handler-функции из `cs.trade.core.ktor`:
- `postHandle` — body + validation + mapper в обе стороны + usecase
- `getHandle` — query/path + validation + extractor + mapToModel + mapToDto + usecase
- `getHandleOutput` — то же, но без `mapToModel` (usecase принимает строку)
- `getHandleInput` — то же, но без `mapToDto` (usecase отдаёт уже готовый shape)
- `getListHandle` — без параметров, на выходе список
- `deleteHandle` / `deleteHandleWithNoContent` — для удаления

### Регистрация в глобальном Routing.kt

Каждая корневая `configureXxxRouting()` **обязана** быть зарегистрирована в `src/main/kotlin/cs/trade/Routing.kt` → `Application.configureRouting()` → `routing { configureXxxRouting() }`. Без этой строчки роут модуля недоступен — этот шаг забывается чаще всего, и swagger молча показывает пустой раздел.

### Что НЕ делать

- Не вешать `@SerialName` на api-DTO — наше API использует Kotlin-имена полей как есть.
- Не возвращать domain-модель напрямую из ручки — всегда через `mapper.toDto`.
- Не парсить параметры в Extractor (он только достаёт `String`) — типизация в Mapper (`String::toLong`), правила в Validation.
- Не делать монолитный `XxxValidation` / `XxxApiMapper` / `XxxRouting` на весь модуль — разбивать по смысловым группам (как репозитории).
- Не забывать `BadRequest → Error` response в Description — Swagger без неё не покажет shape ошибок.
- Не вызывать `call.receive()` / `call.respond()` напрямую — для этого `*Handle` хелперы.
- Не инжектить зависимости внутри вложенных `route {}` — только на верхнем уровне функции.
- Не забывать зарегистрировать корневую `configureXxxRouting()` в `cs/trade/Routing.kt`.

---

## Модуль `core`

Кросс-модульные примитивы. **Не функциональный модуль** — нет `api/domain/infrastructure` слоёв. Содержит инструменты, на которые опираются все остальные модули; обратной зависимости (core → feature/marketplace/...) нет и быть не должно.

```
cs.trade.core/
├── Constants.kt              — глобальные константы (например, EMPTY_STRING)
├── context/                  — Context (coroutineContext, MDC bootstrap)
├── crypto/                   — AesCrypto для шифрования секретов в БД
├── database/                 — Database (createSeparateDataSource, dbTransaction), кастомные column types (androidDeviceId)
├── exceptions/               — общие исключения, sealed AppError hierarchy
├── ktor/
│   ├── BaseDescription.kt    — база для Swagger Description в api-слое
│   ├── *Handle.kt            — хелперы routing (postHandle, getHandle, ...)
│   ├── api/                  — ApiResponse.Success<T> / Error
│   ├── configs/              — Ktor plugin configs
│   ├── exceptions/           — глобальные StatusPages handlers
│   ├── serializers/          — кастомные KSerializer'ы
│   └── validation/           — BaseValidation + общие Konform extensions (positiveLong)
├── metrics/                  — Micrometer / Prometheus registry, общие counter-фабрики
├── network/
│   ├── clients/              — HTTP/WS клиенты (SteamPoweredProxyNetworkClient, ...) — используются из infrastructure/network/*Impl
│   ├── cookie/, plugins/     — Ktor-client плагины (cookies, content-negotiation, retries)
│   ├── proxy/, ratelimit/    — прокси-обёртки, rate-limiter
│   ├── repositories/         — общие repository-хелперы для сети
│   └── streaming/            — общая инфраструктура для WS-каналов / SharedFlow
├── proxy/                    — конфиг внешнего прокси (Amnezia/Xray)
├── tasks/                    — TaskProgressBar и т.п. вспомогательные классы для тасков
├── time/                     — TimeProvider, Clock — абстракции времени для тестируемости
└── usecases/                 — BaseUseCase, throwException, helper-функции (runCatchingWithLogging, runBlockingWithContext, runNullableBlockingWithContext, parallel{2..5}Fun)
```

### Правила

- **Без бизнес-логики.** Логика конкретного маркета/Steam/анализа — НЕ в core. Если возникло желание положить сюда `XxxMarketplaceLogic` — это ошибка, оно идёт в соответствующий feature-модуль.
- **Никто из core не зависит на функциональные модули.** Не импортируй `cs.trade.steam.*` / `cs.trade.marketplace.*` / `cs.trade.analysis.*` из `cs.trade.core.*`.
- Подпапки `core/` группируют по типу примитива, не по фиче (`network/`, `database/`, `time/` — а не `core/steam/`).
- Скилов под core нет — нет повторяющегося шаблона, классы разнородны. Добавление файлов в core — ручная операция.

## Модуль `di`

Koin-конфигурация. KSP-плагин (`koin-annotations`) генерирует основной граф из аннотаций `@Singleton` / `@Factory` / `@Module` / `@ComponentScan`; здесь — только верхнеуровневая склейка и ручные фабрики, которые KSP сгенерировать не может.

```
cs.trade.di/
├── KoinApplication.kt        — точка инициализации, @KoinApplication со списком модулей
└── modules/
    ├── CoreModule.kt         — ComponentScan("cs.trade.core") + ручные Json-фабрики (@Named "HttpJson" / "WebSocketJson")
    ├── FeatureModule.kt      — ComponentScan("cs.trade.feature")
    ├── SystemModule.kt
    ├── SteamModule.kt
    ├── SchedulerModule.kt
    ├── MarketCsGoModule.kt, LisSkinsModule.kt, TradeOnMarketModule.kt
    ├── AnalysisModule.kt, PulseModule.kt
    └── TradingModule.kt
```

### Правила

- **Один файл-модуль на функциональный модуль проекта.** Появился новый модуль (например, `cs.trade.csfloat`) → отдельный `CsFloatModule.kt` + добавить в `@KoinApplication.modules`.
- Класс модуля размечается `@Module` + `@ComponentScan("cs.trade.{module}")` — этого достаточно, чтобы KSP подхватил всё с `@Singleton` / `@Factory` / `@Singleton(binds = [...])` внутри пакета.
- Ручные `@Singleton fun ...` фабрики — только когда KSP не справляется (внешние библиотечные типы вроде `Json`, `HttpClient`, нестандартная сборка с параметрами). Имена через `@Named("...")` если есть несколько инстансов одного типа.
- `KoinApplication.kt` — единственное место, где модули сводятся вместе. **Порядок** в `modules = [...]` имеет значение для разрешения зависимостей при инициализации: core/feature идут первыми, чтобы остальные могли на них опираться.
- Скилов под `di` нет — добавление модуля делается раз на новый top-level пакет, шаблон минимален (3 строки).

## Модуль `scheduler`

JobRunr-обвязка и определения тасков. См. отдельное правило `jobrunr.md` про оперативные тонкости (фатальный shutdown по storage exceptions и т.п.).

```
cs.trade.scheduler/
├── JobRunrConfig.kt          — инициализация JobRunr (BackgroundJobServer, storage, dashboard:8020, 12 воркеров, poll 15s)
├── ConfigureScheduler.kt     — Application.configureScheduler(): bind к Ktor ApplicationStarted/ApplicationStopping, инжект Database и TaskScheduler
├── JobMdcFilter.kt           — JobRunr JobFilter: кладёт jobId/jobName в MDC на время выполнения
├── KoinJobActivator.kt       — резолвер тасков из Koin-графа (JobActivator)
└── tasks/
    ├── BaseTask.kt           — база для recurring jobs (runBlocking + проверка feature flag)
    ├── BaseTimelessTask.kt   — база для eternal tasks (WS-коннекты): свой CoroutineScope + launch
    ├── TaskScheduler.kt      — start(): scheduleRecurringTasks() + startStartupTasks()
    ├── analysis/             — таски анализа/форкаста
    ├── cache/                — прогрев Caffeine-кешей на старте
    ├── marketplace/
    │   ├── lisskins/         — WS-коннекты + опрос LisSkins
    │   ├── market_csgo/      — обновление items, ордер-буков, истории продаж, WS листингов
    │   └── tradeonmarket/    — аккаунты, инвентарь, баланс, нотификации
    ├── pulse/                — обновление данных Pulse
    ├── steam/                — обновление access/refresh токенов
    └── trading/              — графовая торговля (ProcessBuyOffers, ExpireOffers, Reprice, Cashout, ...)
```

### Правила

- **Группировка по доменному источнику** — `tasks/analysis/`, `tasks/marketplace/{name}/`, `tasks/trading/`, `tasks/steam/`, `tasks/pulse/`, `tasks/cache/`. Не плодить плоский `tasks/`.
- **Никакой бизнес-логики в таске.** Таска — тонкая обёртка над одним UseCase из домена соответствующего модуля. Тело `execute(...)` — одна строка: `startTask(useCase::invoke)` или `startTaskWithList { ... }`.
- **Каждая recurring-таска — `XxxTask : BaseTask()`**, `@Singleton`. Шаблон создаётся через skill `scheduler-task`.
- **Для долгоиграющих background-флоу** (WS-каналы, fan-in/fan-out на `SharedFlow`) — `BaseTimelessTask`. У них вместо `runBlocking` собственный `CoroutineScope.launch`, чтобы не блокировать JobRunr воркер на часах.
- **Регистрация recurring** в `TaskScheduler.scheduleRecurringTasks()` через `BackgroundJob.scheduleRecurrently<T>(TASK_ID, Duration | cron)`. Расписание живёт там, не в самой таске.
- **Startup-only** дёргаются в `TaskScheduler.startStartupTasks()` через прямой `.execute()` (без scheduler). Внимание: если стартап-таска кидает синхронно, остальные после неё не запустятся — `start()` идёт последовательно.
- **Feature flag** проверяется автоматически в `BaseTask.startTask`/`startTaskWithList` по ключу `tasks.{taskId}.enabled`. Если флаг выключен — execute молча выходит. Это используется для безболезненного отключения тасков через Unleash без редеплоя.
- `TASK_ID` — формат `{module}.{snake_case_action}` (`steam.update_steam_access_tokens_task`, `trading.expire_offers`). Используется и как ключ JobRunr-recurring, и как ключ фича-флага.
