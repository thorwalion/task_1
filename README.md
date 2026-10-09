# DeepSeek Server

Серверное приложение на Kotlin/Ktor — шлюз к [DeepSeek API](https://api.deepseek.com)
с хранением диалогов в PostgreSQL.

## Стек

| Компонент | Технология |
|---|---|
| Язык | Kotlin 2.4.20 |
| Веб-фреймворк | Ktor 3.6.0 (Netty) |
| HTTP-клиент к DeepSeek | Ktor Client (CIO) |
| БД | PostgreSQL (драйвер 42.7.13) |
| ORM | Exposed 1.5.0 (DSL) |
| Миграции | Flyway 13.9.0 |
| Пул соединений | HikariCP 7.1.0 |
| JSON | kotlinx.serialization 1.11.0 |
| Сборка | Gradle 9.8 (Kotlin DSL) |

## Структура

```
src/main/kotlin/com/example/deepseek/
├── Application.kt              # точка входа, подключение плагинов, error handling
├── config/AppConfig.kt         # чтение application.conf + переменных окружения
├── db/
│   ├── DatabaseFactory.kt      # HikariCP + Flyway + регистрация в Exposed
│   ├── Tables.kt               # описание таблиц (Exposed DSL)
│   └── ConversationRepository.kt
├── model/Models.kt             # DTO API + сериализация UUID
├── provider/
│   ├── ChatProvider.kt         # абстракция LLM-бэкенда
│   └── DeepSeekChatProvider.kt # вызов /chat/completions (snake_case JSON)
└── routes/ChatRoutes.kt        # /health, /api/v1/chat, /api/v1/conversations
src/main/resources/
├── application.conf            # конфигурация Ktor, DeepSeek, БД
├── logback.xml
└── db/migration/V1__init.sql   # Flyway-миграция
```

## Быстрый старт

Требования: JDK 21+, PostgreSQL 14+ (или Docker).

1. **База данных** — либо через Docker:

   ```bash
   docker compose up -d
   ```

   либо существующий PostgreSQL: создайте базу и пользователя
   `deepseek`/`deepseek` (миграции применятся автоматически при старте).

2. **Ключ API** — задайте переменную окружения (в код ключ не зашивается):

   ```bash
   export DEEPSEEK_API_KEY=sk-...
   ```

   На Windows (PowerShell): `$env:DEEPSEEK_API_KEY = "sk-..."`

3. **Запуск**:

   ```bash
   ./gradlew run        # Linux/macOS
   gradlew.bat run      # Windows
   ```

   Сервер поднимется на `http://localhost:8080`.

## Конфигурация

Все параметры задаются переменными окружения (см. `.env.example`):

| Переменная | По умолчанию | Описание |
|---|---|---|
| `DEEPSEEK_API_KEY` | — (**обязательно**) | Ключ DeepSeek API |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | Базовый URL API |
| `DEEPSEEK_MODEL` | `deepseek-flash` | Модель по умолчанию (можно переопределить в запросе) |
| `DEEPSEEK_REASONING_EFFORT` | `high` | Параметр `reasoning_effort` |
| `DEEPSEEK_THINKING_TYPE` | `disabled` | Параметр `thinking.type` |
| `DEEPSEEK_SYSTEM_PROMPT` | пусто | Системный промпт, добавляется к каждому запросу (в БД не хранится) |
| `DEEPSEEK_TIMEOUT_SECONDS` | `120` | Таймаут запроса к DeepSeek |
| `PORT` | `8080` | Порт сервера |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/deepseek` | JDBC URL |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `deepseek` / `deepseek` | Доступ к БД |

## API

### `GET /health`

Проверка живости сервера и доступности БД.

```bash
curl http://localhost:8080/health
# {"status":"ok"}
```

### `POST /api/v1/chat`

Отправляет сообщение в DeepSeek. Если `conversationId` не передан — создаётся новый
диалог (заголовок — первое сообщение). Вся история диалога передаётся модели,
ответ сохраняется в БД.

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Привет!"}'
```

```json
{
  "conversationId": "c9b8efa2-0638-4975-aad9-2ed530aa6b57",
  "reply": {
    "id": 2,
    "role": "assistant",
    "content": "Здравствуйте! Чем могу помочь?",
    "createdAt": "2026-10-06T12:00:00Z"
  },
  "usage": {"promptTokens": 11, "completionTokens": 9, "totalTokens": 20}
}
```

Продолжение диалога: `{"conversationId":"...","message":"Второй вопрос"}`.
Опциональные поля запроса: `model`, `reasoningEffort`.

### `GET /api/v1/conversations`

Список диалогов (новые сверху): `[{id, title, createdAt, updatedAt, messageCount}]`.

### `GET /api/v1/conversations/{id}`

Диалог целиком со всеми сообщениями.

### `DELETE /api/v1/conversations/{id}`

Удаляет диалог и его сообщения (`204 No Content`).

## Ошибки

Все ошибки возвращаются в едином формате:

```json
{"error":{"code":"not_found","message":"Conversation ... not found"}}
```

Коды: `bad_request`, `invalid_json`, `not_found`, `deepseek_error`
(статус ответа пробрасывается от DeepSeek), `internal_error`, `db_unavailable`.

## Тесты и сборка

```bash
./gradlew test          # 14 тестов: репозиторий + маршруты (H2 in-memory)
./gradlew buildFatJar   # исполняемый jar: build/libs/deepseek-server-all.jar
java -jar build/libs/deepseek-server-all.jar
```

## Безопасность

- Ключ API передаётся только через переменную окружения и никогда не попадает
  в код, логи или БД.
- `.env` и `secrets.conf` в `.gitignore`.
