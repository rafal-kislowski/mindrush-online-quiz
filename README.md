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

All other endpoints (as they are added) are protected by Spring Security (HTTP Basic).
If no user is configured, Spring generates a password and prints it in the startup logs.

## Tests

```powershell
cd backend
.\mvnw.cmd test
```

## License
MIT - see `LICENSE`.
