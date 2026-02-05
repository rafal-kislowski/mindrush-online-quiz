# MindRush - Quiz Online

Spring Boot backend for the MindRush quiz platform.

## Tech stack
- Java 17, Spring Boot 3
- Spring Web, Spring Security (JWT in HttpOnly cookies)
- Spring Data JPA + MySQL
- Docker Compose (MySQL)

## Local setup

### 1) Database (MySQL)
From the repository root:

```bash
docker compose up -d
```

Defaults from `docker-compose.yml`:
- DB: `mindrush`
- User/Pass: `mindrush` / `mindrush`
- Root password: `root`
- Port: `3306`

Optional (if enabled in `docker-compose.yml`):
- phpMyAdmin: `http://localhost:8081` (server: `mysql`)

### 2) Configuration (the `local` profile)
The repo keeps a base config without secrets: `backend/src/main/resources/application.properties`.

Local config lives in a separate file that is ignored by Git: `backend/src/main/resources/application-local.properties`.
Create it locally (or adjust) e.g.:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/mindrush?serverTimezone=UTC
spring.datasource.username=mindrush
spring.datasource.password=mindrush
```

### 3) Run the backend
Windows:

```powershell
cd backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

Linux/macOS:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Alternatively set `SPRING_PROFILES_ACTIVE=local`.

The app starts on `http://localhost:8080`.

## Test endpoint
- `GET /api/health` -> `{"status":"UP"}` (no auth required)

## Frontend (Angular)
The repository contains an Angular app in `frontend/` (dev proxy to backend for cookies + WebSocket).

```powershell
cd frontend
npm install
npm start
```

## Guest session (anonymous)
Creates/refreshes an anonymous guest session backed by DB + an HttpOnly cookie:
- `POST /api/guest/session` -> sets `guestSessionId` cookie
- `DELETE /api/guest/session` -> clears cookie (and revokes the session server-side)
- `GET /api/guest/session` -> returns current session info (including generated `displayName`)
- `POST /api/guest/session/heartbeat` -> keeps the session “alive” (updates `lastSeenAt`, extends expiry), returns `204 No Content`

Notes:
- Guest `displayName` is generated server-side (safe characters, no user input).

### Presence (disconnect handling)
Browsers don’t always fire `beforeunload` / `sendBeacon` (crash, sleep, network loss), so the app also uses a heartbeat + server cleanup:
- Frontend sends `POST /api/guest/session/heartbeat` periodically (every ~10s).
- Backend removes stale guests from **OPEN** lobbies only (never during `IN_GAME`).

Config (optional):
```properties
# How long a guest can be “silent” before being treated as disconnected
lobby.presence.timeout=PT25S

# Cleanup frequency
lobby.presence.cleanup.fixedDelayMs=5000
```

## Auth (email/password)
Authentication uses a short-lived access JWT + a long-lived refresh token, both stored in **HttpOnly cookies**:
- `accessToken` (JWT)
- `refreshToken` (random token, server-side persisted and rotatable)

Endpoints:
- `POST /api/auth/register` -> creates a user (email + nickname) and logs in (sets cookies)
- `POST /api/auth/login` -> logs in (sets cookies)
- `POST /api/auth/refresh` -> rotates refresh token + issues a new access token (sets cookies)
- `POST /api/auth/logout` -> revokes refresh token and clears cookies
- `GET /api/auth/me` -> current authenticated user

Notes:
- The UI shows `displayName` (nickname) in game/lobby instead of email.

## Progression (XP / RP / coins)
Both guests and authenticated users accumulate:
- `xp` (experience, used to calculate level; max level is 99 in UI)
- `rankPoints` (RP, used for ranks/leaderboards)
- `coins` (simple in-game currency)

These values are returned by:
- `GET /api/auth/me` (authenticated user)
- `GET /api/guest/session` (guest session)

### Bootstrap admin (local only)
To create an admin account automatically on startup, set:
```properties
app.bootstrap.admin.email=admin@example.com
app.bootstrap.admin.password=Password123
```
Recommended for dev:
```properties
app.jwt.secret=change-me-long-random
```

## Lobby (guest)
Simple guest lobbies (no login required), identified by a 6-character code.

- `POST /api/lobbies` -> creates a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body: `{ "password": "secret123" }`
- `GET /api/lobbies/{code}` -> fetches lobby state
- `POST /api/lobbies/{code}/join` -> joins a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body (when password is set): `{ "password": "secret123" }`
- `POST /api/lobbies/{code}/password` -> changes lobby privacy (owner only, lobby must be `OPEN`)
  - `{ "password": "secret123" }` -> set/update password (private lobby)
  - `{}` (or blank password) -> clear password (public lobby)
- `POST /api/lobbies/{code}/leave` -> leaves a lobby (requires a valid `guestSessionId` cookie)
- `POST /api/lobbies/{code}/close` -> closes a lobby for new joins (owner only)

Guest lobby limits:
- Max players: `2`

Notes:
- In Postman, call `POST /api/guest/session` first (cookie jar must be enabled) and keep the returned `guestSessionId` cookie for lobby requests.
- `close` prevents new players from joining (status becomes `CLOSED`).
- If the owner leaves and another player remains, ownership is transferred to the remaining player.
- If the last player leaves, the lobby is deleted.
- Leaving the lobby is blocked while a game is in progress (`IN_GAME`).

## Quizzes (read-only)
Public, read-only quiz endpoints (no auth required):
- `GET /api/quizzes` -> list quizzes
- `GET /api/quizzes/{id}` -> quiz details
- `GET /api/quizzes/{id}/questions` -> quiz questions + answer options (does not expose correct answers)

## Admin (quiz management)
Admin-only quiz endpoints (requires `ADMIN` role):
- `GET /api/admin/quizzes` -> list quizzes
- `GET /api/admin/quizzes/{id}` -> quiz detail including questions and correct answers
- `POST /api/admin/quizzes` -> create quiz
- `PUT /api/admin/quizzes/{id}` -> update quiz (title/description/category)
- `DELETE /api/admin/quizzes/{id}` -> delete quiz (and all its questions)
- `POST /api/admin/quizzes/{id}/questions` -> add a question (4 options, exactly 1 correct)
- `PUT /api/admin/quizzes/{id}/questions/{questionId}` -> update question + options (requires option ids, exactly 1 correct)
- `DELETE /api/admin/quizzes/{id}/questions/{questionId}` -> delete question

Optional dev seed data:
- set `app.seed.enabled=true` (e.g. in `application-local.properties`)

## Game (lobby)
Minimal game flow on top of lobbies (intended to be used with real-time updates later; for now you can poll `/state`).

Endpoints (requires a valid `guestSessionId` cookie and being in the lobby):
- `POST /api/lobbies/{code}/game/start` -> starts a game (owner only), body: `{ "quizId": 1 }`
- `GET /api/lobbies/{code}/game/state` -> current state (question/reveal/finished)
- `POST /api/lobbies/{code}/game/answer` -> submit answer, body: `{ "questionId": 123, "optionId": 456 }`
- `POST /api/lobbies/{code}/game/end` -> end game (owner only)

Notes:
- Both players get the same question; answer options are shuffled per player.
- The response includes per-player correctness only in `REVEAL` stage (after everyone answers).
- Scoring uses a base score + a speed bonus for correct answers (faster answers give more points). Ties are broken by total points, then correct answers, then total correct answer time.
- Guest games are time-boxed: `QUESTION` defaults to 10s and `REVEAL` to 3s (configurable via `game.guest.question-duration` / `game.guest.reveal-duration`). The response exposes `stageEndsAt` for countdowns.
- If a player doesn't answer before `stageEndsAt`, the server records it as a wrong answer and the game continues normally.
- Auto-advance can be driven by polling `GET /state`, or by the built-in scheduler (`game.scheduler.enabled=true`).
- Lobby status becomes `IN_GAME` while a game is active, then returns to `OPEN` after the game ends.

## WebSocket (STOMP)
The backend exposes a STOMP WebSocket endpoint for real-time notifications.
This is **event-based** (to keep per-player answer option ordering correct): when you receive an event, fetch the latest state via REST (`GET /api/lobbies/{code}/game/state`).

- WebSocket endpoint: `ws://localhost:8080/ws`
- Topic: `/topic/lobbies/{code}/game`
- Message payload: `{"type":"GAME_UPDATED","lobbyCode":"ABC123","serverTime":"..."}`

If you run the frontend on a different origin, make sure cookies are sent and CORS/allowed origins are configured properly (currently `*` for dev).

## Tests

```powershell
cd backend
.\mvnw.cmd test
```

## License
MIT - see `LICENSE`.
