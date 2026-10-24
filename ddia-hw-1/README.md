# HW1 — мониторинг REST-сервиса

Стенд для сбора HTTP-метрик, автоматической генерации dashboard в Grafana
и фоновой нагрузки. Приложение и генератор написаны на Java 25;
сборка и запуск всех компонентов выполняются через Docker Compose.

## Запуск

Нужен Docker с Linux-контейнерами и Compose v2. Из каталога `ddia-hw-1`:

```sh
docker compose up --build --wait
```

Compose собирает приложение, настраивает мониторинг и доступ к Grafana,
создаёт dashboard и запускает нагрузку. Локальные JDK и Gradle не требуются.
При первом запуске нужен интернет для загрузки образов и зависимостей.

| Компонент | Адрес |
| --- | --- |
| Grafana | http://localhost:3000 — папка **DDIA HW1**, dashboard **Service metrics** |
| REST-сервис | http://localhost:8080 |
| VictoriaMetrics | http://localhost:8428/vmui/ |

Grafana доступна без авторизации с ролью Viewer.
[Вход администратора и получение секретов](infra/grafana/README.md).

Нагрузка длится 15 минут: пять минут разгона до 100 RPS и 10 минут на этом
уровне. Около 15% запросов возвращают запланированные ошибки 400, 404 и 409.
На dashboard появляются метрики всех трёх ручек. После завершения нагрузки
собранные данные остаются доступны в выбранном временном диапазоне.

## Архитектура

**REST-сервис** — приложение бронирования переговорок на Spring Boot 4.1.1
со Spring JDBC и H2 в памяти. [Модель данных и HTTP API](SERVICE.md).

**`http-metrics-spring-boot-starter`** — библиотека, подключённая к приложению
Gradle-зависимостью. Автоконфигурация регистрирует MVC-interceptor: он измеряет
длительность запроса и записывает результат вместе с HTTP-статусом, методом
и шаблоном маршрута. Micrometer экспортирует счётчики и histogram buckets
через Actuator. Параметры экземпляра поступают из environment.

**`dashboard-generator`** — отдельное CLI-приложение с Grafana
Foundation SDK `v0.0.20`. Оно получает список прикладных ручек от работающего
сервиса через `/actuator/mappings`, строит SDK-модель dashboard и публикует её
в Grafana через HTTP API. Повторный запуск обновляет dashboard с тем же UID.

`/actuator/mappings` — штатный endpoint Spring Boot Actuator со списком маршрутов.
При подключённом Actuator его HTTP-доступность задаётся свойством
`management.endpoints.web.exposure.include` (здесь `health,prometheus,mappings`).
Генератор опирается на формат Spring MVC mappings и контракт метрик стартера,
поэтому применим к другим сервисам с такими же интерфейсами, независимо
от их бизнес-логики и хранилища.

**VictoriaMetrics `v1.154.0`** хранит метрики приложения.
**Grafana `13.2.2`** выполняет PromQL-запросы к VictoriaMetrics.
**k6 `2.3.0`** создаёт нагрузку на прикладные ручки.

Порядок запуска задают health checks и завершение одноразовых задач:
сначала настраивается доступ, затем создаётся dashboard и запускается нагрузка. CLI ожидает готовности
приложения и Grafana; пароль администратора используется задачей настройки,
а генератор получает Service Account Token из отдельного read-only volume.

## Dashboard

Для каждой ручки создаются три time series панели:

- **Latency:** p50, p75, p90, p95 и p99 в секундах.
- **RPS:** число завершённых запросов в секунду.
- **HTTP errors:** процент каждого кода 4xx/5xx от всех запросов этой ручки.

Селекторы позволяют выбрать deployment, датацентр и под. Rate суммируются
по выбранным экземплярам; перцентили вычисляются по объединённым buckets.
Селектор `Latency HTTP class` влияет только на latency и по умолчанию выбирает
2xx, включая GET/PUT с ответом 200 и POST с ответом 201.
Dashboard помечен `editable=false`.

## Настройки и управление

Необязательные параметры задаются в `.env` рядом с `docker-compose.yaml`.
Для labels используются `METRICS_POD` (по умолчанию `1`), `METRICS_DATACENTER`
(`local`) и `METRICS_DEPLOYMENT` (`booking-dev`). Порты меняются через
`APP_PORT`, `GRAFANA_PORT` и `VICTORIAMETRICS_PORT`.

Состояние и остановка стенда:

```sh
docker compose ps --all
docker compose down
```

Данные H2 теряются при остановке приложения. История метрик, настройки
Grafana и секреты сохраняются в Docker volumes.

- [REST-сервис и примеры запросов](SERVICE.md)
- [Устройство и параметры CLI](dashboard-generator/README.md)
- [Сценарий и параметры нагрузки](load/README.md)
- [Доступ к Grafana и секреты](infra/grafana/README.md)
