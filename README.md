# MindRush - Quiz Online

Spring Boot backend for the MindRush quiz platform.

## Tech stack
- Java 17, Spring Boot 3
- Spring Web, Spring Security (HTTP Basic)
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

## Guest session (anonymous)
Creates/refreshes an anonymous guest session backed by DB + an HttpOnly cookie:
- `POST /api/guest/session` -> sets `guestSessionId` cookie
- `DELETE /api/guest/session` -> clears cookie (and revokes the session server-side)
- `GET /api/guest/session` -> returns current session info (including generated `displayName`)

Notes:
- Guest `displayName` is generated server-side (safe characters, no user input).

All other endpoints (as they are added) are protected by Spring Security (HTTP Basic).
If no user is configured, Spring generates a password and prints it in the startup logs.

## Lobby (guest)
Simple guest lobbies (no login required), identified by a 6-character code.

- `POST /api/lobbies` -> creates a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body: `{ "password": "secret123" }`
- `GET /api/lobbies/{code}` -> fetches lobby state
- `POST /api/lobbies/{code}/join` -> joins a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body (when password is set): `{ "password": "secret123" }`
- `POST /api/lobbies/{code}/leave` -> leaves a lobby (requires a valid `guestSessionId` cookie)
- `POST /api/lobbies/{code}/close` -> closes a lobby for new joins (owner only)

Guest lobby limits:
- Max players: `2`

Notes:
- `close` prevents new players from joining (status becomes `CLOSED`).
- If the owner leaves and another player remains, ownership is transferred to the remaining player.
- If the last player leaves, the lobby is deleted.

## Quizzes (read-only)
Public, read-only quiz endpoints (no auth required):
- `GET /api/quizzes` -> list quizzes
- `GET /api/quizzes/{id}` -> quiz details
- `GET /api/quizzes/{id}/questions` -> quiz questions + answer options (does not expose correct answers)

Optional dev seed data:
- set `app.seed.enabled=true` (e.g. in `application-local.properties`)

## Game (lobby)
Minimal game flow on top of lobbies (intended to be used with real-time updates later; for now you can poll `/state`).

Endpoints (requires a valid `guestSessionId` cookie and being in the lobby):
- `POST /api/lobbies/{code}/game/start` -> starts a game (owner only), body: `{ "quizId": 1 }`
- `GET /api/lobbies/{code}/game/state` -> current state (question/reveal/finished)
- `POST /api/lobbies/{code}/game/answer` -> submit answer, body: `{ "questionId": 123, "optionId": 456 }`
- `POST /api/lobbies/{code}/game/next` -> advance to next question (owner only; only after all players answered)
- `POST /api/lobbies/{code}/game/end` -> end game (owner only)

Notes:
- Both players get the same question; answer options are shuffled per player.
- The response includes per-player correctness only in `REVEAL` stage (after everyone answers).
- Lobby status becomes `IN_GAME` while a game is active, then returns to `OPEN` after the game ends.

## Tests

```powershell
cd backend
.\mvnw.cmd test
```

## License
MIT - see `LICENSE`.
