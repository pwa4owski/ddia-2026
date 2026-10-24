# Grafana

Grafana доступна на `http://localhost:3000`. Анонимный доступ — Viewer.
Источник VictoriaMetrics настраивается из `provisioning/datasources/`,
dashboard создаётся в папке **DDIA HW1**.

При запуске Compose задача `grafana-credentials` создаёт случайный пароль
`bootstrap-admin`. Затем `grafana-bootstrap` подготавливает папку и Service
Account с ролью Editor, выдаёт токен генератору. При повторном запуске
пароль сохраняется, токен заменяется.

| Секрет | Volume | Файл |
| --- | --- | --- |
| Пароль администратора | `grafana-admin` | `/run/grafana-admin/password` |
| Service Account Token | `grafana-generator-token` | `/run/grafana-token/token` |

Права файлов — `0600`, каталогов — `0700`; владельцы — пользователи Grafana
и генератора соответственно. Секреты монтируются потребителям только для чтения.
Генератор получает только токен, пароль используется для настройки Grafana.
Данные и секреты сохраняются после `docker compose down`.

Для входа через браузер используется логин `bootstrap-admin`.
Получить пароль из каталога задания:

```sh
docker compose exec --user 0 -T grafana cat /run/grafana-admin/password
```

Получить токен:

```sh
docker compose run --rm --no-deps --entrypoint cat dashboard-generator /run/grafana-token/token
```

Повторно настроить доступ и обновить dashboard:

```sh
docker compose run --rm --no-deps grafana-bootstrap
docker compose run --rm --no-deps dashboard-generator
```
