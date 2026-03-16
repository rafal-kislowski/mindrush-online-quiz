# MindRush - Quiz Online

Full-stack quiz platform (Spring Boot backend + Angular frontend).

## Tech stack
- Java 17, Spring Boot 3
- Spring Web, Spring Security (JWT in HttpOnly cookies)
- Spring Data JPA + MySQL
- Flyway (versioned DB migrations)
- Docker Compose (MySQL)

## Production pre-flight
Before deploying on VPS, prepare environment variables and production profile:

1. Copy `.env.example` to your local secret file (`.env`, system env, or host panel vars) and set real values.
2. Use `SPRING_PROFILES_ACTIVE=prod` on server.
3. Ensure `JWT_SECRET`, `APP_CORS_ALLOWED_ORIGINS`, `APP_FRONTEND_BASE_URL` are set.
4. For existing databases created before Flyway:
   - keep `APP_FLYWAY_BASELINE_ON_MIGRATE=true` (default),
   - first start will create Flyway history baseline.
5. Rotate any previously used API/SMTP credentials before public demo deployment.

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

Optional (admin AI question generation, disabled by default):

```properties
OPENAI_ENABLED=true
OPENAI_API_KEY=your-openai-api-key
OPENAI_MODEL=gpt-4o-mini
OPENAI_MAX_QUESTIONS_PER_REQUEST=100
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
- `POST /api/auth/register` -> creates a user (email + nickname) and sends verification email
- `POST /api/auth/login` -> logs in (sets cookies)
- `POST /api/auth/refresh` -> rotates refresh token + issues a new access token (sets cookies)
- `POST /api/auth/logout` -> revokes refresh token and clears cookies
- `GET /api/auth/me` -> current authenticated user
- `POST /api/auth/verification/resend` -> sends verification email again (generic response, no account enumeration)
- `POST /api/auth/verify-email` -> verifies account email using one-time token
- `POST /api/auth/password/forgot` -> sends password reset email (generic response, no account enumeration)
- `POST /api/auth/password/reset` -> sets a new password using one-time reset token

Notes:
- The UI shows `displayName` (nickname) in game/lobby instead of email.
- Verification and reset emails are rendered with Thymeleaf HTML templates (`backend/src/main/resources/templates/mail/*`).
- Password reset and email verification tokens are hashed server-side and stored in `auth_action_tokens`.
- Login/refresh for unverified accounts is blocked by default (`app.auth.require-verified-email=true`).

Mail configuration (example):
```properties
app.mail.enabled=true
app.mail.from=no-reply@mindrush.example
app.mail.support-email=support@mindrush.example
app.mail.frontend-base-url=https://app.mindrush.example
app.mail.verify-path=/verify-email
app.mail.reset-path=/reset-password
app.mail.verify-ttl=PT24H
app.mail.reset-ttl=PT30M
app.mail.verify-resend-cooldown=PT2M
app.mail.reset-request-cooldown=PT2M
spring.mail.host=smtp.example.com
spring.mail.port=587
spring.mail.username=...
spring.mail.password=...
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
```

Local Gmail quick start (development):
- Create a Google App Password (recommended for SMTP) and use that password, not your normal account password.
- Set variables before starting backend (PowerShell example):

```powershell
$env:APP_MAIL_ENABLED="true"
$env:APP_MAIL_FROM="your-address@gmail.com"
$env:APP_MAIL_SUPPORT="your-address@gmail.com"
$env:APP_FRONTEND_BASE_URL="http://localhost:4200"
$env:APP_MAIL_VERIFY_RESEND_COOLDOWN="PT2M"
$env:APP_MAIL_RESET_REQUEST_COOLDOWN="PT2M"
$env:SMTP_HOST="smtp.gmail.com"
$env:SMTP_PORT="587"
$env:SMTP_USERNAME="your-address@gmail.com"
$env:SMTP_PASSWORD="your-16-char-app-password"
$env:SMTP_AUTH="true"
$env:SMTP_STARTTLS="true"
```

- If you need temporary local behavior without mandatory activation, set `APP_AUTH_REQUIRE_VERIFIED_EMAIL=false`.

## API error format
Validation and business errors return a consistent JSON body:

```json
{
  "timestamp": "2026-02-19T15:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Validation failed",
  "path": "/api/lobbies",
  "validationErrors": [
    { "field": "password", "message": "PIN must be exactly 4 digits", "rejectedValue": "12" }
  ]
}
```

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

## Lobby
Lobbies are identified by a 6-character code and work for both guests and logged-in users.

- `POST /api/lobbies` -> creates a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body:
    - `{ "password": "1234" }` (4-digit PIN, private lobby)
    - `{ "maxPlayers": 2..5 }` (for guests effectively max 2, for authenticated users 2-5)
- `GET /api/lobbies/{code}` -> fetches lobby state
- `POST /api/lobbies/{code}/join` -> joins a lobby (requires a valid `guestSessionId` cookie)
  - optional JSON body (when PIN is set): `{ "password": "1234" }`
- `POST /api/lobbies/{code}/password` -> changes lobby privacy (owner only, lobby must be `OPEN`)
  - `{ "password": "1234" }` -> set/update PIN (private lobby)
  - `{}` -> clear PIN (public lobby)
- `POST /api/lobbies/{code}/max-players` -> updates max players (owner only)
- `POST /api/lobbies/{code}/selected-quiz` -> updates selected quiz (owner only)
- `POST /api/lobbies/{code}/leave` -> leaves a lobby (requires a valid `guestSessionId` cookie)
- `POST /api/lobbies/{code}/close` -> closes a lobby for new joins (owner only)

Max players:
- Guests: up to `2`
- Authenticated users: `2..5`

Notes:
- In Postman, call `POST /api/guest/session` first (cookie jar must be enabled) and keep the returned `guestSessionId` cookie for lobby requests.
- `close` prevents new players from joining (status becomes `CLOSED`).
- If the owner leaves and another player remains, ownership is transferred to the remaining player.
- If the last player leaves, lobby is marked empty and removed by cleanup scheduler after TTL (`lobby.empty.ttl`, default `PT45S`).
- Leaving the lobby is blocked while a game is in progress (`IN_GAME`).

## Quizzes (read-only)
Public, read-only quiz endpoints (no auth required):
- `GET /api/quizzes` -> list quizzes
- `GET /api/quizzes/{id}` -> quiz details
- `GET /api/quizzes/{id}/questions` -> quiz questions + answer options (does not expose correct answers)

Notes:
- Only quizzes with status `ACTIVE` are exposed publicly. Non-active quizzes return `404`.
- Each quiz item includes `source`:
  - `official` -> system/admin quiz (stored in DB as `quiz_source=OFFICIAL`)
  - `custom` -> user-created and shared quiz (stored in DB as `quiz_source=CUSTOM`)
  - `library` is a frontend view/filter concept (user's own quizzes), not a separate DB enum value.

## Library (user-created quizzes)
Endpoints for authenticated users:
- `GET /api/library/quizzes/mine` -> list your quizzes
- `GET /api/library/quizzes/mine/{id}` -> details with questions + correct answers (owner only)
- `GET /api/library/quizzes/policy` -> current limits + usage counters for your account tier
- `GET /api/library/quizzes/public` -> list public library quizzes (official + approved custom)
- `GET /api/library/quizzes/favorites` -> list your favorited public quizzes
- `POST /api/library/quizzes` -> create quiz draft
- `PUT /api/library/quizzes/{id}` -> update draft/owned quiz
- `POST /api/library/quizzes/{id}/questions` -> add question (4 options, exactly 1 correct)
- `PUT /api/library/quizzes/{id}/questions/{questionId}` -> update question/options
- `DELETE /api/library/quizzes/{id}/questions/{questionId}` -> delete question
- `POST /api/library/quizzes/{id}/submit` -> send quiz to moderation
- `POST /api/library/quizzes/{id}/favorite-toggle` -> add/remove quiz from favorites
- `PUT /api/library/quizzes/{id}/status` -> move draft/trash state (`ACTIVE` cannot be set directly by user)
- `DELETE /api/library/quizzes/{id}` -> move to trash
- `DELETE /api/library/quizzes/{id}/purge` -> permanently delete (only from trash)
- `POST /api/library/media` (`multipart/form-data`, field: `file`) -> upload image, returns `{ "url": "/media/..." }`

Notes:
- Limits are enforced on backend and configurable in `application.properties` under `app.library.policy.*` (`user` and `premium` tiers).
- Default `USER` limits: `20` owned quizzes, `5` published quizzes, `3` pending submissions, `50` max questions/quiz, `10` question images/quiz.
- Submission requires at least `5` questions by default.
- User media URLs in library flows must reference stored files (`/media/...`) and avatar colors must be valid HEX values.
- User-created quiz answer options are text-only (option images are currently disabled for library/user flows).
- Question image limit per tier is configurable via `app.library.policy.<tier>.max-question-images-per-quiz`.
- Upload limits and MIME whitelist are controlled by `app.library.policy.media.*` (default max upload `2MB`).
- Favorite operations are available only for publicly visible quizzes.

## Admin (quiz management)
Admin-only quiz endpoints (requires `ADMIN` role):
- `GET /api/admin/quizzes` -> list quizzes
- `GET /api/admin/quizzes/{id}` -> quiz detail including questions and correct answers
- `POST /api/admin/quizzes` -> create quiz
- `PUT /api/admin/quizzes/{id}` -> update quiz (title/description/category/avatar/game rules)
- `PUT /api/admin/quizzes/{id}/status` -> set quiz status (`DRAFT`, `ACTIVE`, `TRASHED`)
- `DELETE /api/admin/quizzes/{id}` -> move quiz to trash (`TRASHED`)
- `DELETE /api/admin/quizzes/{id}/purge` -> permanently delete quiz (questions/options + stored media)
- `POST /api/admin/quizzes/{id}/questions` -> add a question (4 options, exactly 1 correct)
- `PUT /api/admin/quizzes/{id}/questions/{questionId}` -> update question + options (requires option ids, exactly 1 correct)
- `DELETE /api/admin/quizzes/{id}/questions/{questionId}` -> delete question

### Admin moderation queue
Admin-only submission moderation endpoints:
- `GET /api/admin/quiz-submissions` -> list pending user submissions
- `GET /api/admin/quiz-submissions/{id}` -> submission detail (with moderation context)
- `POST /api/admin/quiz-submissions/{id}/approve` -> approve submission (`expectedSubmissionVersion` required)
- `POST /api/admin/quiz-submissions/{id}/undo-approve` -> move approved submission back to pending review (`expectedSubmissionVersion` required)
- `POST /api/admin/quiz-submissions/{id}/reject` -> reject submission with reason and optional per-question issues
- `POST /api/admin/quiz-submissions/{id}/owner/ban` -> ban submission owner
- `POST /api/admin/quiz-submissions/{id}/owner/unban` -> remove owner ban
- `DELETE /api/admin/quiz-submissions/{id}/avatar` -> remove submission avatar image
- `DELETE /api/admin/quiz-submissions/{id}/questions/{questionId}/image` -> remove question image
- `DELETE /api/admin/quiz-submissions/{id}/questions/{questionId}/options/{optionId}/image` -> remove answer option image

Notes:
- Approve/reject sends both an in-app notification and an email with deep-link to moderation context in library view.
- If quiz becomes unavailable before user opens the link, frontend falls back to dashboard with warning toast.

## User notifications
Persistent user notifications are stored in DB table `user_notifications` and exposed via REST + SSE.

Current categories:
- `moderation` (already used for quiz approval/rejection)
- `reward` (gift-like notifications, reserved for future)
- `news` (platform updates, reserved for future)
- `system` (generic fallback)

Endpoints for authenticated users:
- `GET /api/notifications?limit=50` -> list notifications + unread count
- `POST /api/notifications/{id}/read` -> mark one notification as read
- `POST /api/notifications/{id}/dismiss` -> dismiss notification (removed from list)
- `POST /api/notifications/read-all` -> mark all visible notifications as read
- `GET /api/notifications/stream` -> server-sent events (`connected`, `refresh`) for live navbar updates

Notes:
- Moderation decisions generate notifications automatically when admin approves/rejects a submission.
- Frontend uses SSE to refresh navbar badge/list without page reload.
- Clicking a notification marks it as read and can navigate to target route (for moderation: library with relevant query params).
- Deleting notification is an explicit action (with confirmation in UI) and maps to `dismiss` API.

## Achievements
Authenticated player achievements are calculated from finished games and creator activity.

Endpoints:
- `GET /api/achievements/me` -> achievement catalog with unlock/progress state for current user

Response highlights:
- `title`, `description`
- `totalCount`, `unlockedCount`, `completionPct`
- `items[]` with `key`, `title`, `description`, `icon`, `category`, `tier`, `tierColor`, `target`, `progress`, `unlocked`, `unlockedAt`

Notes:
- Unlocks are persisted in `user_achievement_unlocks`.
- Achievement unlock emits a `reward` notification for the player.

Optional dev seed data:
- Seed is enabled by default for local development (`app.seed.enabled=true`) and runs only when there are no quizzes in DB.
- To disable: set `app.seed.enabled=false`.
- Current seed creates `12` starter quizzes with `5` questions each (good for UI/pagination testing).

## Game (lobby)
Real-time game flow on top of lobbies with REST commands and WebSocket notifications.

Endpoints (requires a valid `guestSessionId` cookie and being in the lobby):
- `POST /api/lobbies/{code}/game/start` -> starts a game (owner only), body: `{ "quizId": 1, "mode": "STANDARD|THREE_LIVES|TRAINING" }`
- `GET /api/lobbies/{code}/game/state` -> current state (question/reveal/finished)
- `POST /api/lobbies/{code}/game/answer` -> submit answer, body: `{ "questionId": 123, "optionId": 456 }`
- `POST /api/lobbies/{code}/game/end` -> end game (owner only)

Notes:
- `mode` is optional on start; default is `STANDARD`.
- `THREE_LIVES` and `TRAINING` are allowed only in solo lobbies (1 player).
- Both players get the same question; answer options are shuffled per player.
- The response includes per-player correctness only in `REVEAL` stage (after everyone answers).
- Scoring uses a base score + a speed bonus for correct answers (faster answers give more points). Ties are broken by total points, then correct answers, then total correct answer time.
- Games are time-boxed:
  - `QUESTION` duration is taken from the quiz settings (`questionTimeLimitSeconds`), default `15s`.
  - `REVEAL` defaults to `3s` (configurable via `game.guest.reveal-duration`).
  - The response exposes `stageEndsAt` and `stageTotalMs` for countdowns.
- In `TRAINING` mode, question stage has no timer (`stageEndsAt=null`, `stageTotalMs=null`).
- Starting a game is blocked if the quiz is not `ACTIVE`.
- If a player doesn't answer before `stageEndsAt`, the server records it as a wrong answer and the game continues normally.
- Auto-advance can be driven by polling `GET /state`, or by the built-in scheduler (`game.scheduler.enabled=true`).
- Lobby status becomes `IN_GAME` while a game is active, then returns to `OPEN` after the game ends.
- `GameStateDto` additionally exposes:
  - `mode` (`STANDARD`, `THREE_LIVES`, `TRAINING`)
  - `gameSessionId`
  - `finishReason` (`COMPLETED`, `MANUAL_END`, `EXPIRED`) when stage is finished
  - `livesRemaining` / `wrongAnswers` for `THREE_LIVES`

## Solo games
Standalone solo sessions (no lobby code needed):

- `POST /api/solo-games/start` -> starts solo game, body: `{ "quizId": 1, "mode": "STANDARD|THREE_LIVES|TRAINING" }`
- `GET /api/solo-games/{gameSessionId}/state` -> current solo state
- `POST /api/solo-games/{gameSessionId}/answer` -> submit answer, body: `{ "questionId": 123, "optionId": 456 }`
- `POST /api/solo-games/{gameSessionId}/end` -> manually finish solo game

Notes:
- User/guest can have only one active game at a time (lobby or solo).
- Starting solo game is blocked if user/guest is currently in a lobby.
- Solo sessions can expire after inactivity (finished with `finishReason=EXPIRED`).
- Always send `questionId` exactly as returned by `/state` (for some solo flows it can be a synthetic negative id).

## Active game lookup
Detect whether current guest has an active game:

- `GET /api/games/current`
  - `200 OK` with payload `{ "type":"SOLO|LOBBY", "gameSessionId":"...", "lobbyCode":"..." }`
  - `204 No Content` if there is no active game

## Casual best record (3 lives)
- `GET /api/casual/three-lives/best` -> best stored result for current guest/user session

Notes:
- Requires valid guest session cookie (call `POST /api/guest/session` first if needed).
- Returns either best record payload (`points`, `answered`, `durationMs`, `updatedAt`) or `null` when no record exists yet.

## WebSocket (STOMP)
The backend exposes STOMP WebSocket channels for lobby/game/chat updates.
Handshake requires a valid `guestSessionId` cookie.

- WebSocket endpoint: `ws://localhost:8080/ws`
- Topics:
  - `/topic/lobbies/{code}/lobby` -> broadcast lobby update event
  - `/topic/lobbies/{code}/game` -> broadcast game update event
  - `/topic/lobbies/{code}/chat` -> lobby chat messages
- User queue:
  - `/user/queue/lobbies/{code}/lobby` -> per-user lobby snapshot (includes viewer-specific fields like `isOwner`, `isParticipant`, owner PIN visibility)

Payload examples:
- Lobby broadcast event:
  - `{"type":"LOBBY_UPDATED","lobbyCode":"ABC123","serverTime":"...","state":null}`
- Lobby per-user snapshot:
  - `{"type":"LOBBY_SNAPSHOT","lobbyCode":"ABC123","serverTime":"...","state":{...}}`
- Game event:
  - `{"type":"GAME_UPDATED","lobbyCode":"ABC123","serverTime":"...","lobbyStatus":"IN_GAME","stage":"QUESTION"}`

If you run the frontend on a different origin, make sure cookies are sent and CORS/allowed origins are configured properly (currently `*` for dev).

## Tests

```powershell
cd backend
.\mvnw.cmd test

cd ..\frontend
npm test -- --watch=false --browsers=ChromeHeadless
```

## License
MIT - see `LICENSE`.
