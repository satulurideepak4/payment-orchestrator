# 💳 Payment Orchestrator

> A production-grade **Payment Orchestration System** built with Java 17 + Spring Boot 3, PostgreSQL, and Redis — inspired by real-world platforms like [Yuno](https://www.y.uno).

---

## 📋 Table of Contents

1. [Project Overview](#-project-overview)
2. [Architecture](#-architecture)
3. [Project Structure](#-project-structure)
4. [Functional Requirements](#-functional-requirements)
5. [Non-Functional Requirements](#-non-functional-requirements)
6. [Tech Stack](#-tech-stack)
7. [Prerequisites](#-prerequisites)
8. [Step-by-Step Setup Guide](#-step-by-step-setup-guide)
9. [Running as Full Docker Stack](#-running-as-full-docker-stack)
10. [Docker Setup Details](#-docker-setup-details)
11. [API Reference](#-api-reference)
12. [Integration Points](#-integration-points)
13. [Payment Lifecycle](#-payment-lifecycle)
14. [Idempotency Design](#-idempotency-design)
15. [Retry and Failover Design](#-retry-and-failover-design)
16. [Test Cases](#-test-cases)
17. [Performance Considerations](#-performance-considerations)
18. [Observability and Metrics](#-observability-and-metrics)
19. [Configuration Reference](#-configuration-reference)
20. [Troubleshooting](#-troubleshooting)
21. [Prompts Used — Vibe Coding Log](#-prompts-used--vibe-coding-log)

---

## 📖 Project Overview

This project implements a **simplified payment orchestration layer** — a system that sits between merchants and multiple downstream payment providers, intelligently routing, retrying, and tracking payments.

**Key capabilities:**

- **Smart Routing** — CARD payments route to Provider A; UPI payments to Provider B. The routing engine is fully open for extension with zero code changes to the core.
- **Idempotency** — Every payment carries a client-supplied idempotency key. Duplicate requests return the original response without reprocessing, using Redis as a fast lookup layer with PostgreSQL as a hard guarantee.
- **Retry and Failover** — On provider failure, the system retries with exponential backoff up to a configurable maximum. Intermediate states are persisted to the DB on each attempt.
- **Payment Status Tracking** — Payments move through `PENDING → PROCESSING → SUCCESS | FAILED`, with every state transition persisted and queryable via API.
- **Observability** — Micrometer metrics exposed at `/actuator/prometheus` covering payment counts, retry rates, and provider latencies.

---

## 🏗 Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                            CLIENT (HTTP)                             │
└──────────────────────────────┬───────────────────────────────────────┘
                               │  POST /api/v1/payments
                               │  GET  /api/v1/payments/{id}
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        CONTROLLER LAYER                              │
│   PaymentController                                                  │
│   • Input validation (Bean Validation / JSR-380)                     │
│   • DTO mapping (CreatePaymentRequest <-> PaymentResponse)           │
│   • HTTP status code management (201 / 200 / 4xx / 5xx)             │
│   • GlobalExceptionHandler — standardised ErrorResponse envelope     │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                    SERVICE LAYER (Orchestration Engine)              │
│   PaymentService                                                     │
│   1. Idempotency check — Redis first, then DB                        │
│   2. Persist payment in PENDING state                                │
│   3. Delegate to RoutingEngine                                       │
│   4. Execute with retry + exponential backoff                        │
│   5. Persist PROCESSING -> SUCCESS | FAILED                          │
│   6. Record Micrometer metrics                                       │
└──────────────────────────────┬───────────────────────────────────────┘
                               │
                               ▼
┌──────────────────────────────────────────────────────────────────────┐
│                          ROUTING ENGINE                              │
│   RoutingEngine                                                      │
│   • Iterates all registered PaymentProviderConnector beans           │
│   • Returns the first connector where supports(method) == true       │
│   • Open/Closed: add new providers without touching routing logic    │
└───────────────────────┬──────────────────────┬───────────────────────┘
                        │                      │
              CARD ─────▼                      ▼───── UPI
┌─────────────────────────┐        ┌─────────────────────────┐
│      PROVIDER A         │        │       PROVIDER B         │
│   ProviderAConnector    │        │   ProviderBConnector     │
│   • Handles CARD        │        │   • Handles UPI          │
│   • ~80% success rate   │        │   • ~85% success rate    │
│   • Records metrics     │        │   • Records metrics      │
└───────────┬─────────────┘        └─────────────┬───────────┘
            └──────────────┬──────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       PERSISTENCE LAYER                              │
│   PaymentRepository (Spring Data JPA + PostgreSQL 15)                │
│   Indexes: idempotency_key (unique), status, merchant_id             │
└──────────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                       IDEMPOTENCY STORE                              │
│   IdempotencyService — Redis 7                                       │
│   Key: idempotency:{key}  |  TTL: 24 hours                           │
│   Graceful fallback to DB on Redis unavailability                    │
└──────────────────────────────────────────────────────────────────────┘
```

### Routing Rules

| Payment Method | Provider   | Connector Class      |
|----------------|------------|----------------------|
| `CARD`         | Provider A | `ProviderAConnector` |
| `UPI`          | Provider B | `ProviderBConnector` |

---

## 📁 Project Structure

```
payment-orchestrator/
│
├── Dockerfile                          # Multi-stage app Docker build
├── docker-compose.yml                  # Full stack: postgres + redis
├── pom.xml                             # Maven build descriptor
├── README.md
│
└── src/
    ├── main/java/com/yuno/payment/
    │   ├── PaymentOrchestratorApplication.java
    │   ├── config/
    │   │   └── RedisConfig.java              # Lettuce connection factory
    │   ├── controller/
    │   │   └── PaymentController.java         # REST endpoints (3 routes)
    │   ├── dto/
    │   │   ├── CreatePaymentRequest.java      # Validated inbound DTO
    │   │   ├── PaymentResponse.java           # Outbound DTO with factory method
    │   │   └── ErrorResponse.java             # Standard error envelope
    │   ├── exception/
    │   │   ├── GlobalExceptionHandler.java    # Centralised HTTP error mapping
    │   │   ├── PaymentNotFoundException.java
    │   │   ├── DuplicateIdempotencyKeyException.java
    │   │   └── NoProviderFoundException.java
    │   ├── idempotency/
    │   │   └── IdempotencyService.java        # Redis + DB fallback
    │   ├── model/
    │   │   ├── Payment.java                   # JPA entity
    │   │   ├── PaymentMethod.java             # CARD | UPI
    │   │   └── PaymentStatus.java             # PENDING | PROCESSING | SUCCESS | FAILED
    │   ├── provider/
    │   │   ├── PaymentProviderConnector.java  # Provider interface (SPI)
    │   │   ├── ProviderAConnector.java        # CARD implementation
    │   │   ├── ProviderBConnector.java        # UPI implementation
    │   │   └── ProviderResponse.java          # Provider result value object
    │   ├── repository/
    │   │   └── PaymentRepository.java         # Spring Data JPA repository
    │   ├── routing/
    │   │   └── RoutingEngine.java             # Provider selection engine
    │   └── service/
    │       └── PaymentService.java            # Orchestration engine
    │
    └── test/
        ├── java/com/yuno/payment/
        │   ├── config/EmbeddedRedisConfig.java
        │   ├── controller/PaymentControllerTest.java
        │   ├── idempotency/IdempotencyServiceTest.java
        │   ├── provider/ProviderConnectorTest.java
        │   ├── routing/RoutingEngineTest.java
        │   └── service/PaymentServiceTest.java
        └── resources/
            └── application-test.yml          # H2 + embedded Redis (port 6370)
```

---

## ✅ Functional Requirements

| # | Requirement             | Status | Implementation                                                          |
|---|-------------------------|--------|-------------------------------------------------------------------------|
| 1 | **Create Payment API**  | ✅      | `POST /api/v1/payments` — validates, persists, routes, retries, returns |
| 2 | **Fetch Payment API**   | ✅      | `GET /api/v1/payments/{id}` and `GET ?merchantId=`                      |
| 3 | **Routing CARD → A**    | ✅      | `RoutingEngine` + `ProviderAConnector.supports(CARD)`                   |
| 4 | **Routing UPI → B**     | ✅      | `ProviderBConnector.supports(UPI)`                                      |
| 5 | **Retry and Failover**  | ✅      | Configurable attempts, exponential backoff, exception-safe loop         |
| 6 | **Idempotency**         | ✅      | Redis TTL 24h + PostgreSQL `UNIQUE` constraint                          |
| 7 | **Status Tracking**     | ✅      | `PENDING → PROCESSING → SUCCESS / FAILED` — persisted each transition  |

---

## 🔒 Non-Functional Requirements

| Category          | Requirement                                                                                   |
|-------------------|-----------------------------------------------------------------------------------------------|
| **Reliability**   | Retry up to N times (default 3, configurable via `payment.retry.max-attempts`)                |
| **Idempotency**   | Redis TTL-based fast path; PostgreSQL `UNIQUE` index as hard database-level guarantee         |
| **Observability** | Micrometer counters + timers; Prometheus endpoint at `/actuator/prometheus`                   |
| **Resilience**    | Redis failures caught and logged — system continues with DB-only idempotency                  |
| **Security**      | Bean Validation on all inputs; parameterised JPA queries prevent SQL injection; non-root Docker |
| **Performance**   | HikariCP pool; four DB indexes; Redis eliminates duplicate DB reads on retried requests       |
| **Scalability**   | Stateless service — horizontally scalable; shared Redis + PostgreSQL as coordination layer    |
| **Testability**   | Layered architecture; all deps injectable; H2 + embedded Redis for zero-infra test runs       |
| **Portability**   | Full Docker Compose stack; multi-stage Dockerfile; environment-variable-driven config         |

---

## 🛠 Tech Stack

| Component          | Technology                                       | Version  |
|--------------------|--------------------------------------------------|----------|
| Language           | Java                                             | 17       |
| Framework          | Spring Boot                                      | 3.2.4    |
| Build Tool         | Apache Maven                                     | 3.9+     |
| Database           | PostgreSQL                                       | 15       |
| Cache/Idempotency  | Redis                                            | 7        |
| ORM                | Spring Data JPA + Hibernate                      | 6.x      |
| Redis Client       | Lettuce (via Spring Data Redis)                  | 6.x      |
| Validation         | Jakarta Bean Validation (JSR-380)                | 3.x      |
| Metrics            | Micrometer + Prometheus                          | 1.12+    |
| Testing            | JUnit 5 + Mockito + MockMvc + H2 + Embedded Redis | —       |
| Containerisation   | Docker + Docker Compose                          | 24+      |

---

## 📋 Prerequisites

Before starting, ensure the following are installed:

| Tool              | Min Version | Check Command              | Install Guide                          |
|-------------------|-------------|----------------------------|----------------------------------------|
| **Java JDK**      | 17          | `java -version`            | https://adoptium.net                   |
| **Maven**         | 3.8         | `mvn -version`             | https://maven.apache.org/install.html  |
| **Docker**        | 24.0        | `docker --version`         | https://docs.docker.com/get-docker/    |
| **Docker Compose**| 2.0 (v2)    | `docker compose version`   | Bundled with Docker Desktop            |
| **Git**           | 2.x         | `git --version`            | https://git-scm.com/downloads          |
| **curl** (optional) | any       | `curl --version`           | Pre-installed on macOS/Linux           |

> **Windows users:** Use Git Bash or WSL for the commands below.

---

## 🚀 Step-by-Step Setup Guide

Follow these steps in order. Each step includes a verification command so you know it succeeded.

---

### Step 1 — Clone the Repository

```bash
git clone https://github.com/<your-username>/payment-orchestrator.git
cd payment-orchestrator
```

**Verify:**
```bash
ls
# Expected: Dockerfile  README.md  docker  docker-compose.yml  pom.xml  src
```

---

### Step 2 — Verify All Prerequisites

```bash
# Java 17+
java -version
# Expected: openjdk version "17.x.x"

# Maven 3.8+
mvn -version
# Expected: Apache Maven 3.x.x

# Docker is running
docker info
# Expected: Server Version, Containers, Images info (no error)

# Docker Compose v2
docker compose version
# Expected: Docker Compose version v2.x.x
```

If any check fails, install the missing tool before proceeding.

---

### Step 3 — Start Infrastructure (PostgreSQL + Redis)

This builds the custom Docker images and starts both services.

```bash
docker compose up -d
```

**What this does step by step:**
1. Builds PostgreSQL 15 image
2. On first start, runs `docker/postgres/init.sql` automatically, which:
   - Creates the `payments` table with all columns and constraints
   - Creates 4 indexes (idempotency_key, status, merchant_id, composite)
   - Adds an `updated_at` auto-update trigger
   - Inserts 3 seed rows for immediate verification
3. Builds Redis 7 image
4. Enables RDB + AOF persistence and sets a 256MB memory limit
5. Maps port `5432` (PostgreSQL) and `6379` (Redis) to your localhost

**Expected terminal output:**
```
[+] Building ...
[+] Running 3/3
 ✔ Network payment-net           Created
 ✔ Container payment_postgres    Started
 ✔ Container payment_redis       Started
```

---

### Step 4 — Verify Infrastructure is Healthy

```bash
docker compose ps
```

**Expected output** — both containers must show `(healthy)`:
```
NAME                STATUS              PORTS
payment_postgres    Up X seconds (healthy)   0.0.0.0:5432->5432/tcp
payment_redis       Up X seconds (healthy)   0.0.0.0:6379->6379/tcp
```

> If status shows `(starting)`, wait 20–30 seconds and recheck. The PostgreSQL `init.sql` takes a few seconds to run on first start.

**Verify PostgreSQL schema was created:**
```bash
docker exec -it payment_postgres psql -U postgres -d payment_orchestrator \
  -c "SELECT idempotency_key, status, payment_method FROM payments;"
```

Expected (3 seed rows):
```
  idempotency_key  | status  | payment_method
-------------------+---------+----------------
 seed-card-001     | SUCCESS | CARD
 seed-upi-001      | SUCCESS | UPI
 seed-card-002     | FAILED  | CARD
(3 rows)
```

**Verify Redis is responding:**
```bash
docker exec -it payment_redis redis-cli ping
```

Expected:
```
PONG
```

---

### Step 5 — Build the Application

```bash
mvn clean package -DskipTests
```

> Skipping tests here since you have not yet started the app. Run tests in Step 9 after the app is verified.

**Expected output (last lines):**
```
[INFO] BUILD SUCCESS
[INFO] Total time: XX.XXX s
```

The packaged JAR is at:
```
target/payment-orchestrator-1.0.0.jar
```

---

### Step 6 — Run the Application

**Option A — Maven (recommended for development):**
```bash
mvn spring-boot:run
```

**Option B — JAR directly:**
```bash
java -jar target/payment-orchestrator-1.0.0.jar
```

**Option C — JAR with explicit config (if ports differ):**
```bash
java -jar target/payment-orchestrator-1.0.0.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/payment_orchestrator \
  --spring.datasource.username=postgres \
  --spring.datasource.password=postgres \
  --spring.data.redis.host=localhost \
  --spring.data.redis.port=6379 \
  --payment.retry.max-attempts=3
```

**Expected startup output (look for these lines):**
```
INFO  c.y.p.PaymentOrchestratorApplication  - Started PaymentOrchestratorApplication in X.XXX seconds
INFO  c.y.p.routing.RoutingEngine           - RoutingEngine initialised with 2 connectors: [ProviderA, ProviderB]
```

The server is now listening on **http://localhost:8080**

---

### Step 7 — Verify the Application is Healthy

```bash
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
```

**Expected response:**
```json
{
   "status": "UP",
   "components": {
      "db": {
         "status": "UP",
         "details": {
            "database": "PostgreSQL",
            "validationQuery": "isValid()"
         }
      },
      "diskSpace": {
         "status": "UP",
         "details": {
            "total": 494384795648,
            "free": 228438466560,
            "threshold": 10485760,
            "path": "/Users/deepaksatuluri/Movies/payment-orchestrator/.",
            "exists": true
         }
      },
      "ping": {
         "status": "UP"
      },
      "redis": {
         "status": "UP",
         "details": {
            "version": "7.2.13"
         }
      }
   }
}
```

All three components (`db`, `diskSpace`, `redis`) must show `UP` before proceeding.

---

### Step 8 — Make Your First API Calls

#### 8a. Create a CARD payment

```bash
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "payment-for-ear-phones",
    "merchantId": "apple-inc",
    "amount": 99.99,
    "currency": "USD",
    "paymentMethod": "CARD"
  }'
```

**Expected response (201 Created):**
```json
{
   "id": "6cf75063-6a9b-404a-87a3-feffe7b3d598",
   "idempotencyKey": "payment-for-ear-phones",
   "merchantId": "apple-inc",
   "amount": 99.99,
   "currency": "USD",
   "paymentMethod": "CARD",
   "status": "SUCCESS",
   "providerName": "ProviderA",
   "providerReference": "PA-AAEAB224",
   "statusMessage": "Card payment authorised",
   "retryCount": 0,
   "createdAt": "2026-04-09T12:27:55.942595",
   "updatedAt": "2026-04-09T12:27:56.064085"
}
```

> **Note:** Due to the simulated 20% failure rate in ProviderA, `status` may occasionally be `FAILED` if all retries are exhausted. Use a different `idempotencyKey` to try again.

---

#### 8b. Test idempotency — send the same request twice

```bash
# Run this EXACT same command a second time
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "payment-for-ear-phones",
    "merchantId": "apple-inc",
    "amount": 99.99,
    "currency": "USD",
    "paymentMethod": "CARD"
  }'
```

Both responses will have the **same `id`, `status`, and `providerReference`** — the payment was not processed twice.

---

#### 8c. Create a UPI payment

```bash
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "my-first-upi-001",
    "merchantId": "merchant-demo",
    "amount": 500.00,
    "currency": "INR",
    "paymentMethod": "UPI"
  }'
```

The response will show `"providerName": "ProviderB"` — UPI was routed correctly.

---

#### 8d. Fetch a payment by ID

```bash
# Replace <UUID> with the id from step 8a
curl -s http://localhost:8080/api/v1/payments/<UUID>
```

---

#### 8e. List all payments for a merchant

```bash
curl -s "http://localhost:8080/api/v1/payments?merchantId=merchant-demo"
```

---

#### 8f. Test a validation error

```bash
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "bad-001",
    "merchantId": "merchant-demo",
    "amount": 0,
    "currency": "DOLLAR",
    "paymentMethod": "CARD"
  }'
```

**Expected (400 Bad Request):**
```json
{
    "status": 400,
    "error": "Validation Failed",
    "message": "One or more fields are invalid",
    "fieldErrors": {
        "amount": "amount must be greater than zero",
        "currency": "currency must be a 3-letter ISO 4217 code (e.g. USD)"
    }
}
```

---

### Step 9 — Run the Full Test Suite

Tests use **H2 in-memory database** and **embedded Redis on port 6370** — no Docker services needed.

```bash
mvn test
```

**Expected output:**
```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Run a specific test class:**
```bash
mvn test -Dtest=PaymentServiceTest
mvn test -Dtest=PaymentControllerTest
mvn test -Dtest=RoutingEngineTest
mvn test -Dtest=IdempotencyServiceTest
mvn test -Dtest=ProviderConnectorTest
```

**Generate an HTML test report:**
```bash
mvn surefire-report:report
# Report at: target/site/surefire-report.html
```

---

## 🐳 Running as Full Docker Stack

To run the complete stack (app + postgres + redis) entirely in containers:

```bash
# Step 1: Build the JAR first
mvn clean package -DskipTests

# Step 2: Build and start all three containers
docker compose up -d

# Step 3: Follow the app logs until it starts
docker compose logs -f app

# Step 4: Start the Application
# Wait for: "Started PaymentOrchestratorApplication in X.XXX seconds"
```

**Verify all services are healthy:**
```bash
docker compose ps
```

Expected:
```
NAME                STATUS               PORTS
payment_postgres    Up X seconds (healthy)   0.0.0.0:5432->5432/tcp
payment_redis       Up X seconds (healthy)   0.0.0.0:6379->6379/tcp
```

**Useful Docker commands:**

```bash
# View live app logs
docker compose logs -f app

# Restart just the app (e.g. after a JAR rebuild)
docker compose restart app

# Stop all containers (data is preserved in volumes)
docker compose down

# Full reset — stop containers AND delete all volumes
docker compose down -v

# Open PostgreSQL CLI
docker exec -it payment_postgres psql -U postgres -d payment_orchestrator

# Open Redis CLI
docker exec -it payment_redis redis-cli
```

---
## 📡 API Reference

### Base URL

```
http://localhost:8080/api/v1
```

---

### POST `/api/v1/payments` — Create Payment

Creates and processes a new payment. Idempotent — same `idempotencyKey` always returns the original response.

**Request Body:**

| Field            | Type     | Required | Constraint                                  | Example                    |
|------------------|----------|----------|---------------------------------------------|----------------------------|
| `idempotencyKey` | `string` | ✅        | 8–128 characters                            | `"order-99999-attempt-1"`  |
| `merchantId`     | `string` | ✅        | Max 64 characters                           | `"merchant-abc"`           |
| `amount`         | `number` | ✅        | Greater than 0.00, max 15 integer digits    | `250.00`                   |
| `currency`       | `string` | ✅        | Exactly 3 uppercase letters (ISO 4217)      | `"USD"`, `"INR"`, `"EUR"`  |
| `paymentMethod`  | `enum`   | ✅        | Exactly `CARD` or `UPI`                     | `"CARD"`                   |

**Response fields:**

| Field               | Type       | Description                                            |
|---------------------|------------|--------------------------------------------------------|
| `id`                | `UUID`     | System-generated payment UUID                          |
| `idempotencyKey`    | `string`   | Echo of the submitted key                              |
| `merchantId`        | `string`   | Echo of the merchant ID                                |
| `amount`            | `number`   | Echo of the amount                                     |
| `currency`          | `string`   | Echo of the currency                                   |
| `paymentMethod`     | `enum`     | `CARD` or `UPI`                                        |
| `status`            | `enum`     | `PENDING`, `PROCESSING`, `SUCCESS`, or `FAILED`        |
| `providerName`      | `string`   | `ProviderA` or `ProviderB`                             |
| `providerReference` | `string`   | Provider transaction ID (null if failed)               |
| `statusMessage`     | `string`   | Human-readable result or error description             |
| `retryCount`        | `integer`  | Number of provider call attempts made                  |
| `createdAt`         | `datetime` | ISO 8601 creation timestamp                            |
| `updatedAt`         | `datetime` | ISO 8601 last update timestamp                         |

**HTTP status codes:**

| Code  | Condition                                        |
|-------|--------------------------------------------------|
| `201` | New payment created and processed                |
| `200` | Idempotent replay — original response returned   |
| `400` | Validation failed — `fieldErrors` map in body    |
| `422` | No provider available for requested method       |
| `500` | Unexpected server error                          |

---

### GET `/api/v1/payments/{id}` — Fetch Payment

| Parameter | Type   | Required | Description  |
|-----------|--------|----------|--------------|
| `id`      | `UUID` | ✅        | Payment UUID |

**Status codes:** `200` found | `400` invalid UUID | `404` not found

---

### GET `/api/v1/payments?merchantId={id}` — List by Merchant

| Parameter    | Type     | Required | Description          |
|--------------|----------|----------|----------------------|
| `merchantId` | `string` | ✅        | Merchant identifier  |

Returns array of payment responses. Empty array `[]` if merchant has no payments.

**Status codes:** `200` always (may be empty) | `400` missing param

---

### Actuator Endpoints

| Endpoint                      | Description                         |
|-------------------------------|-------------------------------------|
| `GET /actuator/health`        | Service health (db + redis + disk)  |
| `GET /actuator/metrics`       | Available metric names              |
| `GET /actuator/prometheus`    | Full Prometheus scrape output       |

---

## 🔗 Integration Points

### PostgreSQL

| Property         | Value                                                                                     |
|------------------|-------------------------------------------------------------------------------------------|
| Host             | `localhost:5432`                                                                          |
| Database         | `payment_orchestrator`                                                                    |
| Table            | `payments`                                                                                |
| Unique key       | `idempotency_key`                                                                         |
| Indexes          | `idx_idempotency_key` (unique), `idx_payment_status`, `idx_merchant_id`, `idx_merchant_status` |
| Connection pool  | HikariCP — max 10 connections                                                             |
| Input            | `Payment` entity from JPA                                                                 |
| Output           | `Payment` entity / `List<Payment>`                                                        |

### Redis

| Property       | Value                               |
|----------------|-------------------------------------|
| Host           | `localhost:6379`                    |
| Key pattern    | `idempotency:{idempotencyKey}`      |
| Value          | Payment UUID string                 |
| TTL            | 24 hours                            |
| Eviction       | `allkeys-lru` (256MB cap)           |
| Fallback       | DB unique constraint on Redis down  |

### Provider A — CARD

| Property           | Value                                   |
|--------------------|-----------------------------------------|
| Triggers on        | `PaymentMethod.CARD`                    |
| Class              | `ProviderAConnector`                    |
| Input              | `Payment` entity                        |
| Output             | `ProviderResponse` (success/failure VO) |
| Reference format   | `PA-XXXXXXXX`                           |
| Simulated success  | ~80%                                    |
| Real-world analog  | Stripe, Adyen, Braintree                |

### Provider B — UPI

| Property           | Value                                   |
|--------------------|-----------------------------------------|
| Triggers on        | `PaymentMethod.UPI`                     |
| Class              | `ProviderBConnector`                    |
| Input              | `Payment` entity                        |
| Output             | `ProviderResponse` (success/failure VO) |
| Reference format   | `PB-XXXXXXXX`                           |
| Simulated success  | ~85%                                    |
| Real-world analog  | Razorpay, PhonePe, Paytm                |

---

## 🔄 Payment Lifecycle

```
Client: POST /api/v1/payments
             │
             ▼
   Idempotency Check
   ├── Redis HIT  ──────────────────────────────► Return existing (200 OK)
   ├── DB HIT (Redis miss) ─────────────────────► Return existing (200 OK)
   └── MISS
             │
             ▼
   Persist status=PENDING, store key in Redis
             │
             ▼
   RoutingEngine.route(paymentMethod)
   ├── CARD ──► ProviderAConnector
   └── UPI  ──► ProviderBConnector
             │
             ▼
   Retry Loop (attempt 1 to maxAttempts)
   ├── Persist status=PROCESSING
   ├── Call provider.process(payment)
   │   ├── SUCCESS ──► break out of loop
   │   └── FAILURE / EXCEPTION ──► increment retryCount
   │                               exponential backoff
   │                               next attempt
   └── All attempts failed
             │
             ▼
   Persist final status (SUCCESS or FAILED)
             │
             ▼
   Return PaymentResponse (201 Created)
```

---

## 🛡 Idempotency Design

**Layer 1 — Redis (fast path, sub-millisecond):**
- Every `createPayment` call first checks `IdempotencyService.get(key)`
- On hit: fetches payment from DB and returns immediately — no provider call
- Keys stored with 24-hour TTL

**Layer 2 — PostgreSQL (hard guarantee):**
- If Redis is down or key was evicted: DB query by `idempotency_key`
- On hit: payment returned and Redis re-warmed for next call
- DB `UNIQUE` constraint acts as last line of defence against concurrent duplicates

**Correctness guarantee:** The system is correct even if Redis is entirely unavailable — it is just slightly slower.

---

## 🔁 Retry and Failover Design

| Aspect               | Value                                                 |
|----------------------|-------------------------------------------------------|
| Max attempts         | Configurable via `payment.retry.max-attempts` (default: 3) |
| Backoff formula      | `100ms × 2^attempt`: 200ms, 400ms, 800ms...           |
| Backoff cap          | 2 seconds                                             |
| Exception handling   | Runtime exceptions from provider treated as failures  |
| Intermediate persist | `PROCESSING` + `retryCount` written to DB per attempt |
| Exhausted retries    | Payment marked `FAILED`, all history preserved        |

---

## 🧪 Test Cases

**41 tests** across 5 test classes, fully self-contained (H2 + embedded Redis on port 6370).

| ID  | Test Name                                              | Class       | Type        | Positive/Negative |
|-----|--------------------------------------------------------|-------------|-------------|-------------------|
| T01 | CARD payment routes to ProviderA                       | Routing     | SANITY      | ✅ Positive        |
| T02 | UPI payment routes to ProviderB                        | Routing     | SANITY      | ✅ Positive        |
| T03 | ProviderA supports CARD, not UPI                       | Routing     | SANITY      | ✅ Positive        |
| T04 | ProviderB supports UPI, not CARD                       | Routing     | SANITY      | ✅ Positive        |
| T05 | No connectors registered → NoProviderFoundException    | Routing     | REGRESSION  | ❌ Negative        |
| T06 | Only ProviderB present, CARD request → exception       | Routing     | REGRESSION  | ❌ Negative        |
| T07 | Only ProviderA present, UPI request → exception        | Routing     | REGRESSION  | ❌ Negative        |
| T08 | New CARD payment created, returns response             | Service     | SANITY      | ✅ Positive        |
| T09 | New UPI payment routed to ProviderB                    | Service     | SANITY      | ✅ Positive        |
| T10 | Duplicate request returns existing from Redis          | Service     | SANITY      | ✅ Positive        |
| T11 | Duplicate request returns from DB (Redis miss)         | Service     | SANITY      | ✅ Positive        |
| T12 | Provider fails once, succeeds on retry                 | Service     | REGRESSION  | ✅ Positive        |
| T13 | All retries exhausted → payment marked FAILED          | Service     | REGRESSION  | ❌ Negative        |
| T14 | Provider throws runtime exception → retried gracefully | Service     | REGRESSION  | ❌ Negative        |
| T15 | Fetch existing payment by UUID                         | Service     | SANITY      | ✅ Positive        |
| T16 | Fetch non-existent payment → PaymentNotFoundException  | Service     | REGRESSION  | ❌ Negative        |
| T17 | List payments by merchant returns all records          | Service     | SANITY      | ✅ Positive        |
| T18 | List for unknown merchant returns empty list           | Service     | REGRESSION  | ✅ Positive        |
| T19 | POST valid CARD payment → 201 Created                  | Controller  | INTEGRATION | ✅ Positive        |
| T20 | POST missing idempotencyKey → 400 with field error     | Controller  | INTEGRATION | ❌ Negative        |
| T21 | POST missing merchantId → 400 with field error         | Controller  | INTEGRATION | ❌ Negative        |
| T22 | POST amount = 0 → 400 with field error                 | Controller  | INTEGRATION | ❌ Negative        |
| T23 | POST negative amount → 400                             | Controller  | INTEGRATION | ❌ Negative        |
| T24 | POST invalid currency (e.g. DOLLAR) → 400              | Controller  | INTEGRATION | ❌ Negative        |
| T25 | POST null paymentMethod → 400 with field error         | Controller  | INTEGRATION | ❌ Negative        |
| T26 | POST empty JSON body → 400                             | Controller  | INTEGRATION | ❌ Negative        |
| T27 | GET existing payment by UUID → 200 with body           | Controller  | INTEGRATION | ✅ Positive        |
| T28 | GET payment with malformed UUID → 400                  | Controller  | INTEGRATION | ❌ Negative        |
| T29 | GET payments by merchantId → 200 with list             | Controller  | INTEGRATION | ✅ Positive        |
| T30 | GET payments with no merchantId param → 400            | Controller  | INTEGRATION | ❌ Negative        |
| T31 | Redis get() returns stored paymentId for known key     | Idempotency | SANITY      | ✅ Positive        |
| T32 | Redis get() returns null for unknown key               | Idempotency | SANITY      | ✅ Positive        |
| T33 | Redis store() calls SET with 24-hour TTL               | Idempotency | SANITY      | ✅ Positive        |
| T34 | Redis remove() calls DELETE                            | Idempotency | SANITY      | ✅ Positive        |
| T35 | Redis down → get() returns null gracefully             | Idempotency | REGRESSION  | ❌ Negative        |
| T36 | Redis down → store() does not throw                    | Idempotency | REGRESSION  | ❌ Negative        |
| T37 | Redis down → remove() does not throw                   | Idempotency | REGRESSION  | ❌ Negative        |
| T38 | ProviderA name returns 'ProviderA'                     | Provider    | SANITY      | ✅ Positive        |
| T39 | ProviderA process() returns non-null response          | Provider    | SANITY      | ✅ Positive        |
| T40 | ProviderResponse.success() sets correct fields         | Provider    | SANITY      | ✅ Positive        |
| T41 | ProviderResponse.failure() sets correct fields         | Provider    | SANITY      | ✅ Positive        |

**Summary: 41 tests | 24 positive | 17 negative | 0 failures**

---

## ⚡ Performance Considerations

### Database Index Strategy

```sql
CREATE UNIQUE INDEX idx_idempotency_key  ON payments (idempotency_key);   -- O(log n) duplicate check
CREATE INDEX idx_payment_status          ON payments (status);             -- Status-based queries
CREATE INDEX idx_merchant_id             ON payments (merchant_id);        -- Merchant listing
CREATE INDEX idx_merchant_status         ON payments (merchant_id, status); -- Most selective filter
```

### Redis Cache Impact

| Scenario                              | Without Redis            | With Redis               |
|---------------------------------------|--------------------------|--------------------------|
| Duplicate request detection           | 1 DB round trip ~5ms     | Redis lookup ~0.1ms      |
| 1,000 req/s with 10% retry rate       | 100 extra DB queries/s   | Near-zero extra DB load  |

### HikariCP Pool Sizing

Default is 10 connections. For production, tune using:
```
pool_size = (core_count × 2) + effective_spindle_count
```
For a 4-core server with SSD: `(4 × 2) + 1 = 9`, rounded up to 10.

### Retry Backoff Profile

```
Attempt 1 fails → wait 200ms
Attempt 2 fails → wait 400ms
Attempt 3 fails → FAILED (total extra delay: ~600ms)
```
Capped at 2s per wait to keep P99 latency bounded.

---

## 📊 Observability and Metrics

After startup, all metrics are at:
```
GET http://localhost:8080/actuator/prometheus
```

| Metric                          | Labels                  | Description                        |
|---------------------------------|-------------------------|------------------------------------|
| `payment_processed_total`       | `status`, `provider`    | Total completed payments           |
| `payment_retry_total`           | `provider`, `attempt`   | Total retry attempts               |
| `payment_processing_duration`   | `provider`              | End-to-end processing latency      |
| `provider_process_duration`     | `provider`              | Provider API call latency          |
| `provider_response_total`       | `provider`, `result`    | Provider success/failure count     |

**Example Prometheus queries:**
```promql
# Payment success rate over last 5 minutes
rate(payment_processed_total{status="success"}[5m])
  / rate(payment_processed_total[5m])

# P99 latency
histogram_quantile(0.99, rate(payment_processing_duration_seconds_bucket[5m]))
```

---

## ⚙️ Configuration Reference

All settings are in `src/main/resources/application.yml` and can be overridden via environment variables.

| Property                                  | Default                                             | Description                    |
|-------------------------------------------|-----------------------------------------------------|--------------------------------|
| `spring.datasource.url`                   | `jdbc:postgresql://localhost:5432/payment_orchestrator` | PostgreSQL JDBC URL        |
| `spring.datasource.username`              | `postgres`                                          | DB username                    |
| `spring.datasource.password`              | `postgres`                                          | DB password                    |
| `spring.datasource.hikari.maximum-pool-size` | `10`                                             | Max DB connections             |
| `spring.data.redis.host`                  | `localhost`                                         | Redis hostname                 |
| `spring.data.redis.port`                  | `6379`                                              | Redis port                     |
| `spring.data.redis.timeout`               | `2000ms`                                            | Redis command timeout          |
| `payment.retry.max-attempts`              | `3`                                                 | Max provider retry attempts    |
| `server.port`                             | `8080`                                              | HTTP server port               |

---

## 🔧 Troubleshooting

### "Connection refused: localhost:5432"

PostgreSQL container is not running or not yet healthy.

```bash
docker compose ps          # check status
docker compose up -d postgres  # start if down
docker compose logs postgres   # check for errors
```

---

### "Connection refused: localhost:6379"

Redis container is not running.

```bash
docker compose up -d redis
docker exec -it payment_redis redis-cli ping   # should return PONG
```

---

### "Address already in use: 8080"

Another process is using port 8080.

```bash
# macOS/Linux
lsof -i :8080
# Windows
netstat -ano | findstr :8080

# Run on a different port
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8090"
```

---

### Tests fail — "Could not connect to embedded Redis"

Port 6370 is in use on your machine.

```bash
lsof -i :6370    # find what is using it
kill <PID>
mvn test
```

---

### Payment always returns FAILED

The provider simulators have a random failure rate (20% ProviderA, 15% ProviderB). With 3 attempts, ~0.8% of calls will still fail. Use a new `idempotencyKey` to retry, or increase attempts:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--payment.retry.max-attempts=5"
```

---

### docker compose up fails — "permission denied" on config files

```bash
chmod +r docker/postgres/init.sql
chmod +r docker/redis/redis.conf
docker compose up -d postgres redis
```

---

## 💬 Prompts Used 

As required by the Yuno assessment, all AI prompts used during development are documented here.

---

### Prompt 1 — Project Scaffolding and Architecture

> "Design a Java 17 Spring Boot 3 payment orchestration system with the following architecture: Client → Controller → Service (Orchestration Engine) → Routing Engine → Provider Connectors (A and B) → Persistence (PostgreSQL via JPA) → Idempotency Store (Redis). Functional requirements: Create Payment API, Fetch Payment API, routing where CARD goes to Provider A and UPI goes to Provider B, retry with failover, idempotency, payment status tracking (PENDING → PROCESSING → SUCCESS/FAILED). Use Maven. Follow SOLID principles — especially the Open/Closed principle so new providers can be added without modifying the routing engine. Produce a full pom.xml with Spring Boot 3.2, Spring Data JPA, Spring Data Redis, Spring Validation, Micrometer Prometheus, Lombok, PostgreSQL driver, H2 for tests, and embedded Redis for tests."

---

### Prompt 2 — Payment Entity and Status Lifecycle

> "Create a JPA entity class Payment for a PostgreSQL table 'payments'. Fields: UUID id (primary key, auto-generated), idempotencyKey (unique, varchar 128), merchantId (varchar 64), amount (BigDecimal, precision 19 scale 4, must be > 0), currency (char 3 ISO 4217), paymentMethod (enum CARD/UPI), status (enum PENDING/PROCESSING/SUCCESS/FAILED), providerName, providerReference, statusMessage, retryCount (int, default 0), createdAt (auto-set on create), updatedAt (auto-updated). Add JPA @Index annotations on idempotencyKey (unique), status, and merchantId. Use Lombok. Add Javadoc explaining the status state machine."

---

### Prompt 3 — Open/Closed Routing Engine

> "Create a RoutingEngine Spring @Component that accepts a List<PaymentProviderConnector> via constructor injection. The route(PaymentMethod) method iterates the list and returns the first connector where supports(method) returns true. If no match, throw NoProviderFoundException. This design must be Open for extension (add new connector bean) and Closed for modification (no changes to RoutingEngine). Log the registered connectors at startup (INFO) and the routing decision at DEBUG."

---

### Prompt 4 — Provider Connectors with Micrometer Metrics

> "Create ProviderAConnector (handles CARD) and ProviderBConnector (handles UPI) both implementing PaymentProviderConnector interface with getName(), supports(PaymentMethod), and process(Payment). Each connector: simulates latency (50-150ms), simulates failure at a configurable rate using Math.random(), records a Micrometer Timer for process duration and a Counter for success/failure tagged by provider name, returns a ProviderResponse value object (success boolean, providerReference, message, errorCode). ProviderA failure rate 20%, ProviderB 15%."

---

### Prompt 5 — Redis Idempotency Service with Graceful Degradation

> "Create IdempotencyService backed by StringRedisTemplate. Methods: get(key) returns stored paymentId or null, store(key, paymentId) with 24-hour TTL, remove(key). All methods must catch ALL exceptions and fail silently with a WARN log — never propagate Redis errors to callers. Key format: 'idempotency:{key}'. The system must degrade gracefully to DB-level idempotency if Redis is down."

---

### Prompt 6 — Orchestration Engine with Retry and Exponential Backoff

> "Create PaymentService in Spring. createPayment must: (1) check Redis for idempotency key, return existing if found; (2) check DB, re-warm Redis and return if found; (3) persist PENDING and store in Redis; (4) route via RoutingEngine; (5) retry loop up to payment.retry.max-attempts — each attempt: persist PROCESSING, call connector.process(), on success exit, on failure or exception increment retryCount and apply exponential backoff (100ms * 2^attempt capped at 2s); (6) mark FAILED if all retries exhausted; (7) persist final state, record Micrometer metrics, return PaymentResponse."

---

### Prompt 7 — REST Controller and Centralised Error Handling

> "Create PaymentController at /api/v1/payments: POST (create, @Valid, 201/200), GET /{id} (fetch by UUID, 200/404), GET ?merchantId= (list, 200). Create GlobalExceptionHandler mapping MethodArgumentNotValidException → 400 with fieldErrors map, PaymentNotFoundException → 404, DuplicateIdempotencyKeyException → 409, NoProviderFoundException → 422, Exception → 500. All errors use ErrorResponse DTO (status, error, message, timestamp, fieldErrors). CreatePaymentRequest: idempotencyKey @NotBlank @Size(8,128), merchantId @NotBlank, amount @NotNull @DecimalMin(0.01), currency @Pattern([A-Z]{3}), paymentMethod @NotNull."

---

### Prompt 8 — Comprehensive Test Suite with Classification

> "Write JUnit 5 tests for: RoutingEngineTest (CARD/UPI routing, 3 negative cases: no connectors, missing CARD, missing UPI provider); PaymentServiceTest (mock all deps, cover: new payment, Redis idempotency hit, DB idempotency hit, retry-then-succeed, all-retries-fail, exception-from-provider, fetch success, fetch not found, list-by-merchant, unknown merchant); PaymentControllerTest (WebMvcTest, all validation failures, happy paths); IdempotencyServiceTest (mock Redis, test get/store/remove + 3 graceful-degradation cases); ProviderConnectorTest (names, supports(), response factories). Classify every test as [SANITY], [REGRESSION], or [INTEGRATION] in @DisplayName."

---

### Prompt 9 — Docker Infrastructure

> "Create: docker/postgres/Dockerfile extending postgres:15-alpine with HEALTHCHECK; docker/postgres/init.sql creating payments table, ENUM types, 4 indexes, updated_at trigger, 3 seed rows; docker/redis/Dockerfile extending redis:7-alpine with custom redis.conf and HEALTHCHECK; docker/redis/redis.conf with RDB + AOF persistence, 256MB maxmemory allkeys-lru, lazy freeing; root Dockerfile as multi-stage build (maven builder + JRE runtime, non-root user, container JVM flags); docker-compose.yml with build contexts for all 3 services, healthchecks, named volumes pgdata and redisdata, custom bridge network payment-net, depends_on with condition service_healthy."

---

### Prompt 10 — Full Documentation

> "Write a comprehensive README for the payment orchestrator covering: project overview, full architecture ASCII diagram, project directory tree, functional requirements table with status, non-functional requirements table, tech stack table, prerequisites table with check commands, step-by-step setup guide (10 steps each with verify command), full Docker stack instructions, Docker image design details, complete API reference with request/response field tables and HTTP status codes, integration points tables for PostgreSQL/Redis/ProviderA/ProviderB, payment lifecycle flow diagram, idempotency design explanation, retry/backoff design table, full test case table (41 tests with classification and positive/negative), performance considerations, Prometheus metrics table with example queries, full configuration reference table, troubleshooting section for 6 common issues, and the vibe coding prompt log."

---