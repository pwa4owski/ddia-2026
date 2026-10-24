# Фоновая нагрузка

Сценарий `bookings.js` выполняется в k6 `2.3.0`. Compose запускает его
после готовности сервисов и генерации dashboard.

Нагрузка растёт с 1 до 100 RPS за пять минут, затем держится 10 минут.
85% запросов успешны, остальные поровну возвращают 400, 404 и 409:
некорректные данные, отсутствующая бронь и занятый слот.
Перед запуском создаются тестовые брони; GET и PUT используют их,
POST добавляет новые. Сценарий можно повторять без очистки базы.

Параметры задаются в `.env` рядом с `docker-compose.yaml`:

| Параметр | По умолчанию |
| --- | --- |
| `LOAD_TARGET_RPS` | `100` |
| `LOAD_RAMP_DURATION` | `5m` |
| `LOAD_HOLD_DURATION` | `10m` |
| `LOAD_ERROR_PERCENT` | `15` |

Команды из каталога задания:

```sh
docker compose logs -f load-generator
docker compose stop load-generator
docker compose up -d --no-deps --force-recreate load-generator
```

Короткий прогон на работающем стенде, после остановки основной нагрузки:

```sh
docker compose run --rm --no-deps -e LOAD_TARGET_RPS=100 -e LOAD_RAMP_DURATION=10s -e LOAD_HOLD_DURATION=30s load-generator
```

Неожиданные статусы, сетевые ошибки и пропущенные итерации завершают k6
с ошибкой. Запланированные 4xx учитываются отдельно в `intentional_http_errors`.
