# Генератор dashboard

Java CLI с JCommander и Grafana Foundation SDK `v0.0.20` для Grafana `13.2.2`.
Получает ручки приложения через `/actuator/mappings` и создаёт для каждой
панели latency, RPS и процентов HTTP-ошибок. Dashboard находится в папке
**DDIA HW1** и недоступен для редактирования.

Генератор запускается автоматически через Docker Compose после готовности
приложения, Grafana и токена доступа. Повторный запуск обновляет тот же dashboard.

Из каталога задания:

```sh
docker compose run --rm --no-deps --build dashboard-generator
```

Параметры CLI:

| Параметр | Назначение |
| --- | --- |
| `--app-url` | Адрес приложения |
| `--grafana-url` | Адрес Grafana |
| `--token-file` | Файл с Service Account Token, обязателен |
| `--datasource-uid` | Источник метрик, по умолчанию `victoriametrics` |
| `--folder-uid` | Папка dashboard, по умолчанию `ddia-hw-1` |
| `--dashboard-title` | Заголовок, по умолчанию `Service metrics` |
| `--metric-name` | Гистограмма, по умолчанию `bookings_request_seconds` |

В Compose адреса и файл токена уже настроены. Справка:

```sh
docker compose run --rm --no-deps dashboard-generator --help
```

[Архитектура и запуск стенда](../README.md) · [Доступ к Grafana](../infra/grafana/README.md)
