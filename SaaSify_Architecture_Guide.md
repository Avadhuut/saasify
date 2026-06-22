# SaaSify — Architecture & Implementation Guide (Detailed Edition)

This guide provides an end-to-end overview of the architecture, database designs, messaging payloads, flow sequences, and resilience fallbacks of the SaaSify multi-tenant microservices platform.

---

## 1. Database Schema Boundaries & ERD Layout

SaaSify segregates data into two distinct zones: the **Administrative Master Catalog** (`saasify_master`) and the **Isolated Tenant Sandboxes** (`tenant_{subdomain}`).

```
                                +-------------------+
                                |  saasify_master   |
                                +-------------------+
                                | - tenants         |
                                | - usage_history   |
                                | - outbox_log      |
                                +-------------------+
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  ▼ (Dynamic Provisioning via Flyway)             ▼
       +-----------------------+                       +-----------------------+
       |     tenant_acme       |                       |     tenant_globex     |
       +-----------------------+                       +-----------------------+
       | - users               |                       | - users               |
       | - audit_log           |                       | - audit_log           |
       +-----------------------+                       +-----------------------+
```

### A. Master Schema Catalog Tables (`saasify_master`)

#### `tenants` (Active subscription details)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(36)` | `PRIMARY KEY` | Unique tenant UUID. |
| `name` | `VARCHAR(100)` | `NOT NULL` | Registered company name. |
| `subdomain` | `VARCHAR(50)` | `UNIQUE`, `NOT NULL` | Subdomain key (e.g. `acme`). |
| `contact_email` | `VARCHAR(100)` | `NOT NULL` | Admin email address. |
| `plan` | `VARCHAR(20)` | `NOT NULL` | Active plan tier (`FREE`, `PRO`, `ENTERPRISE`). |
| `status` | `VARCHAR(20)` | `NOT NULL` | Tenant status (`ACTIVE`, `SUSPENDED`). |
| `created_at` | `TIMESTAMP` | `NOT NULL` | Onboarding record timestamp. |

#### `tenant_usage_history` (Historical aggregated statistics)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(36)` | `PRIMARY KEY` | Unique record UUID. |
| `tenant_id` | `VARCHAR(36)` | `FOREIGN KEY` | Reference back to `tenants.id`. |
| `date` | `DATE` | `NOT NULL` | Logged calendar date. |
| `api_calls` | `BIGINT` | `NOT NULL` | Sum of API requests processed on that date. |

#### `outbox_log` (Transactional events log)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(36)` | `PRIMARY KEY` | Event tracking UUID. |
| `type` | `VARCHAR(50)` | `NOT NULL` | Event classification (`TENANT_CREATED`, etc.). |
| `payload` | `TEXT` | `NOT NULL` | Target JSON message payload. |
| `processed` | `BOOLEAN` | `DEFAULT FALSE` | Processing flag for scheduler dispatch. |
| `created_at` | `TIMESTAMP` | `NOT NULL` | Creation timestamp. |

### B. Tenant Schema Catalog Tables (`tenant_{subdomain}`)

#### `users` (Isolated tenant-scoped accounts)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(36)` | `PRIMARY KEY` | User UUID. |
| `email` | `VARCHAR(100)` | `UNIQUE`, `NOT NULL` | Login email (unique *only* within this schema). |
| `password` | `VARCHAR(255)` | `NOT NULL` | BCrypt encrypted credentials. |
| `role` | `VARCHAR(20)` | `NOT NULL` | Permissions level (`ADMIN`, `MEMBER`). |

#### `audit_log` (Local activity traces)
| Column | Type | Constraints | Description |
| :--- | :--- | :--- | :--- |
| `id` | `VARCHAR(36)` | `PRIMARY KEY` | Record UUID. |
| `user_id` | `VARCHAR(36)` | `NOT NULL` | Actor identifier. |
| `action` | `VARCHAR(100)` | `NOT NULL` | Mapped activity trace (e.g. `CREATE_USER`). |
| `resource` | `VARCHAR(255)` | `NULL` | Mapped resource reference. |
| `timestamp` | `TIMESTAMP` | `NOT NULL` | Trace execution timestamp. |

---

## 2. Kafka Event Payload Contracts

SaaSify exchanges metadata asynchronously via Apache Kafka. These contracts define the JSON structures used in the communication payload.

### A. Tenant Created Event (`tenant.created`)
Dispatched to notify downstream services that a new tenant has registered, enabling local pool configuration updates.
```json
{
  "tenantId": "f7d2bc5c-b10b-4eb4-b903-f3612d1b88e1",
  "name": "Acme Corp",
  "subdomain": "acme",
  "plan": "FREE",
  "status": "ACTIVE",
  "contactEmail": "admin@acme.com",
  "createdAt": "2026-06-22T12:00:00.000Z"
}
```

### B. Tenant Suspended Event (`tenant.suspended`)
Dispatched when a tenant account is blocked, triggering immediate session invalidations in the authentication service.
```json
{
  "tenantId": "f7d2bc5c-b10b-4eb4-b903-f3612d1b88e1",
  "subdomain": "acme",
  "status": "SUSPENDED",
  "reason": "PAYMENT_OVERDUE",
  "timestamp": "2026-06-22T12:15:30.000Z"
}
```

### C. Tenant Plan Upgraded Event (`tenant.plan.upgraded`)
Dispatched on plan upgrades, enabling instant quota cache updates at the API Gateway.
```json
{
  "tenantId": "f7d2bc5c-b10b-4eb4-b903-f3612d1b88e1",
  "subdomain": "acme",
  "oldPlan": "FREE",
  "newPlan": "PRO",
  "timestamp": "2026-06-22T12:30:00.000Z"
}
```

### D. API Usage Recorded Event (`usage.recorded`)
Asynchronously logs request occurrences, incrementing daily consumption counters.
```json
{
  "tenantId": "acme",
  "route": "/api/users",
  "method": "GET",
  "timestamp": "2026-06-22T12:35:10.000Z"
}
```

---

## 3. Step-by-Step Scenario Traces

### Scenario 1: Dynamic Tenant Onboarding Flow

```
Client         TenantController       TenantService       SchemaProvisionService       OutboxLog
  │                  │                     │                        │                      │
  │───(POST req)────>│                     │                        │                      │
  │                  │───(createTenant)───>│                        │                      │
  │                  │                     │──(provisionSchema)────>│                      │
  │                  │                     │                        │──(CREATE SCHEMA)──  │
  │                  │                     │                        │──(Run Flyway)─────>  │
  │                  │                     │<──(Success)────────────│                      │
  │                  │                     │                                               │
  │                  │                     │────────────────────────────────(Write Log)───>│
  │                  │                     │<───────────────(Save Tenant Record)───────────│
  │                  │<──(Success Response)│
  ▼                  ▼                     ▼
```

1. **Client Signup Request**: The client sends a `POST /api/tenants` payload containing subdomain `"acme"` to the Gateway.
2. **Controller Routing**: The Gateway routes the payload to `TenantController.java` in `tenant-service`.
3. **Database Transaction Start**: `TenantService.java` opens a transaction in the `saasify_master` catalog database.
4. **Schema Creation**: The service invokes `SchemaProvisioningService.java`. It executes a `CREATE DATABASE tenant_acme` JDBC statement against the database.
5. **Flyway Migrations**: The provisioning service starts a Flyway runner bound to connection pool targeting the new schema, executing `db/template` DDL migrations dynamically.
6. **Outbox Entry**: If DDL setup completes successfully, the service writes a `TENANT_CREATED` event log entry to the `outbox_log` database table.
7. **Commit & Return**: The transaction commits in `saasify_master`, saving the new tenant status as `ACTIVE` and returning a `201 Created` response.

---

### Scenario 2: Request Flow & Daily Quota Checks

```
Client             Gateway:QuotaCheckFilter         Redis Cache          Downstream Service
  │                           │                          │                        │
  │────(GET /api/users)──────>│                          │                        │
  │                           │────(INCR quota:acme)---->│                        │
  │                           │<───(Return count: 75)────│                        │
  │                           │                                                   │
  │                           │───(Under limit, route)───────────────────────────>│
  │                           │<──(API Response)──────────────────────────────────│
  │<──(Return User Data)──────│
  ▼                           ▼
```

1. **Request Interception**: The Gateway receives an incoming request `GET /api/users` containing the headers `Authorization: Bearer <JWT>` and `X-Tenant-ID: acme`.
2. **Filter Execution**: `QuotaCheckFilter.java` intercept the request:
   * Decodes the tenant subdomain context from headers/token.
   * Increments the daily Redis counter key `quota:acme:yyyyMMdd` atomically using the command `redisTemplate.opsForValue().increment(key)`.
3. **Evaluation**:
   * *Under Limits*: If the returned count is `75` (under the plan threshold of 100), the request is forwarded downstream to `user-service`.
   * *Over Limits*: If the count is `101` (exceeding limits), routing stops. The Gateway returns `429 Too Many Requests` containing the daily UTC reset timestamp `resetAt` parameter.

---

### Scenario 3: Account Suspension & Session Eviction

```
Client         TenantController       TenantService       OutboxLog       Kafka Broker       AuthService:Consumer
  │                  │                     │                  │                 │                     │
  │───(PUT req)─────>│                     │                  │                 │                     │
  │                  │───(suspendTenant)──>│                  │                 │                     │
  │                  │                     │──(Update db status)                │                     │
  │                  │                     │──(Save Outbox Event)──────────────>│                     │
  │                  │                     │<─(Commit Transaction)              │                     │
  │                  │                     │                                    │                     │
  │                  │                     │   [Outbox Publisher Poller Runs]   │                     │
  │                  │                     │   ├───(Send Event)────────────────>│                     │
  │                  │                     │   └───(Mark Processed)──> [DB]     │                     │
  │                  │                     │                                    │──(tenant.suspended)>│
  │                  │                     │                                    │                     │──(SCAN refresh:acme:*)
  │                  │                     │                                    │                     │──(Delete Session Keys)
  ▼                  ▼                     ▼
```

1. **Trigger Suspension**: The administrator updates the tenant status using `PUT /api/tenants/{id}/status?status=SUSPENDED`.
2. **Transaction Log**: `TenantService.java` changes the status flag to `SUSPENDED` in `saasify_master.tenants` and inserts a `TENANT_SUSPENDED` outbox log in the transaction context.
3. **Outbox Polling**: The publisher scheduler polls the log entry, sends a message to the `tenant.suspended` Kafka topic, and sets `processed = true` in the outbox table.
4. **Session Eviction**: The consumer `TenantSuspendedConsumer.java` in `auth-service` reads the Kafka message, queries Redis for the tenant's pattern `refresh:acme:*`, and deletes all active refresh tokens.
5. **Gateway Block**: When the user makes their next request, the Gateway check filters find no valid session and block access with HTTP 403 Forbidden.

---

## 4. Fault Tolerance & Fallback Matrix

SaaSify implements fallback mechanisms using Resilience4j to protect services against downstream service failures:

| Trigger Scenario | Target Call | Protective Mechanism | Fallback Behavior |
| :--- | :--- | :--- | :--- |
| **Tenant Service Offline** | `user-service` calling `tenantServiceClient.getTenantDetails(tenantId)` | **Circuit Breaker & Retry** | Fall back to safe local thresholds (binds user limits to `FREE` defaults: Max 5 users). |
| **Kafka Broker Offline** | `api-gateway` recording request metrics | **Direct Buffer fallback** | Gateway logs metrics warnings locally and continues request routing without client-facing errors. |
| **MySQL Master Offline** | `billing-service` processing usage events | **Exponential Retry Pipeline** | Kafka retries the event 4 times with progressive delays (`2s -> 4s -> 8s`). If still offline, it routes to `usage.recorded.DLQ`. |
| **Redis Server Offline** | Gateway checking per-minute rate-limits | **Fail-Open Policy** | Rate limiting is bypassed, logging Redis warnings but allowing legitimate client traffic to proceed. |

---

## 5. Local Setup & Startup Sequence

Follow this step-by-step sequence to run SaaSify on your local machine:

### Step 1: Stage Environment
1. Clone the repository to your local workspace directory.
2. Ensure you have **JDK 17 or 23** and **Maven 3.8+** installed.
3. Start Docker Desktop.

### Step 2: Spin Up Backing Infrastructure
Run Docker Compose in the root folder to start MySQL, Redis, Kafka, and the observability stack:
```bash
docker compose up -d
```
Verify that all containers are healthy:
```bash
docker ps
```

### Step 3: Build the Codebase
Build and compile all Maven microservice modules:
```bash
mvn clean install -DskipTests
```

### Step 4: Start the Microservices
Start the services in the following order (either by running the compiled JARs or launching them via your IDE):

1. **eureka-server**: Wait for the registry to start on port `8761`.
2. **api-gateway**: Start the Gateway on port `8080`.
3. **tenant-service**: Boot on port `8081` (it will automatically migrate the `saasify_master` schema).
4. **auth-service**: Boot on port `8082`.
5. **user-service**: Boot on port `8083`.
6. **billing-service**: Boot on port `8084`.

### Step 5: Verify Deployment
1. Open the Eureka dashboard at [http://localhost:8761/](http://localhost:8761/) and verify that all microservices register successfully.
2. Import the Postman collection `postman/saasify.postman_collection.json` to begin onboarding tenants, registering users, and testing API limits.
