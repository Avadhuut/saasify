# SaaSify — Ultimate System Architecture, Codebase & Verification Guide

Welcome to the master reference documentation for **SaaSify**. This guide combines architectural blueprints, file-by-file codebase details, annotated execution traces, operational runbooks, and design Q&As into a single learning resource.

---

## 1. Architectural Overview & System Design

SaaSify utilizes a distributed microservices architecture designed to support dynamic multi-tenancy. Rather than grouping all tenant data into shared tables using logical filters (row-level separation), SaaSify enforces separation at the database engine level via **schema-per-tenant isolation**.

```
                                +---------------------------+
                                |    Client Application     |
                                +---------------------------+
                                              │
                                              ▼ (HTTP Request with header / JWT)
                                +---------------------------+
                                |   Spring Cloud Gateway    | <---> [ Redis Cache ]
                                |        (Port 8080)        |       - Rate Limiting
                                +---------------------------+       - Daily Quotas
                                              │
                    ┌─────────────────────────┴─────────────────────────┐
                    ▼ (Load-Balanced Route)                             ▼ (Load-Balanced Route)
          +--------------------+                              +--------------------+
          |    auth-service    |                              |    user-service    |
          |    (Port 8082)     |                              |    (Port 8083)     |
          +--------------------+                              +--------------------+
             │              │                                    │              │
             │ (DB Pool)    │ (Kafka Event)                      │ (DB Pool)    │ (Feign Call)
             ▼              ▼                                    ▼              ▼
     [tenant_acme]    [tenant.suspended]                 [tenant_globex]  [tenant-service]
     [tenant_globex]        │                                               (Port 8081)
                            ▼                                                   │
                  +--------------------+                                        ▼
                  |  billing-service   | <─────────── [Kafka] ───────────── [Outbox Log]
                  |    (Port 8084)     |       (tenant.created / updated)
                  +--------------------+
```

### Database Schema Boundaries

Data is segregated into the **Administrative Master Catalog** (`saasify_master`) and the **Isolated Tenant Sandboxes** (`tenant_{subdomain}`).

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

#### A. Master Schema Catalog Tables (`saasify_master`)
* **`tenants`**: Stores global registration profiles, metadata, subscription levels, and status flags.
* **`tenant_usage_history`**: Holds daily aggregated API consumption stats for historical reports.
* **`outbox_log`**: Transactional outbox table to queue Kafka events alongside service database updates.

#### B. Tenant Schema Catalog Tables (`tenant_{subdomain}`)
* **`users`**: Isolated user credentials and roles. Email identifiers are unique only within their own schema boundary.
* **`audit_log`**: Tracks local operations (e.g. `CREATE_USER`) with activity metadata and timestamps.

---

## 2. Microservice & Codebase Deep Dive

### A. eureka-server (Discovery Registry)
* **Purpose**: Serves as the central server registry. All core microservices register their host/port combinations dynamically on startup.
* **Key Files**:
  * [EurekaServerApplication.java](file:///c:/Users/Admin/Desktop/saasify/eureka-server/src/main/java/com/saasify/eureka/EurekaServerApplication.java): Standard Spring Boot server initialization class annotated with `@EnableEurekaServer`.

### B. api-gateway (Central Gatekeeper)
* **Purpose**: The front-facing router. Centralizes token verification, daily quotas, rate limiting, and distributed trace tags.
* **Key Files**:
  * [ApiGatewayApplication.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/ApiGatewayApplication.java): Configures route mappings matching incoming URLs to target services.
  * [JwtValidationFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/JwtValidationFilter.java): Checks JWT signatures and asserts that the `tenantId` claim matches the `X-Tenant-ID` header.
  * [QuotaCheckFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/QuotaCheckFilter.java): Increments daily Redis counters. Blocks suspended accounts with HTTP 403 and limits with HTTP 429 (returning reset parameters).
  * [RateLimitFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/RateLimitFilter.java): Sliding-window rate limiter per tenant. If exceeded, returns HTTP 429 with the `Retry-After` header.
  * [TenantMetricsImpl.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/metrics/TenantMetricsImpl.java): Binds request metrics (counter, latencies) with tenant tags to Prometheus.

### C. tenant-service (Administrative Catalog)
* **Purpose**: Manages global tenant listings, subscription states, dynamic Flyway migrations, and outbox logs.
* **Key Files**:
  * [TenantController.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/controller/TenantController.java): API controllers handling tenant onboarding, plan changes, and suspension commands.
  * [SchemaProvisioningService.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/SchemaProvisioningService.java): Creates a new MySQL database schema and runs dynamic Flyway migrations using standard DDL SQL scripts.
  * [TenantService.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/TenantService.java): Handles business operations. Inserts outbox events in the same database transaction.
  * [OutboxPublisherScheduler.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/OutboxPublisherScheduler.java): Background task polling `outbox_log` to publish events asynchronously to Kafka.

### D. auth-service (Identity Provider)
* **Purpose**: Manages logins, user registrations, and session revocations.
* **Key Files**:
  * [AuthController.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/controller/AuthController.java): Exposes login and registration REST endpoints.
  * [AuthService.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/service/AuthService.java): Validates credentials using `BCryptPasswordEncoder` and signs JWTs.
  * [TenantSuspendedConsumer.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/kafka/TenantSuspendedConsumer.java): Kafka listener that reads `tenant.suspended` and invalidates all session refresh tokens in Redis.

### E. user-service (Resource Manager)
* **Purpose**: Coordinates CRUD operations for users inside isolated tenant databases.
* **Key Files**:
  * [UserController.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/controller/UserController.java): REST mappings to view, add, or update user settings.
  * [UserService.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/service/UserService.java): Implements logic. Checks user limits via Feign and hashes passwords with BCrypt before creating database records.
  * [TenantServiceClient.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/feign/TenantServiceClient.java): Declarative Feign client communicating with `tenant-service` under Resilience4j protection.

### F. billing-service (Usage Aggregator)
* **Purpose**: Consumes API metrics from Kafka, tracks active gauges, and runs daily archive resets.
* **Key Files**:
  * [BillingController.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/controller/BillingController.java): REST endpoints displaying live daily totals and archived histories.
  * [UsageEventConsumer.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/kafka/UsageEventConsumer.java): Processes usage events from Kafka, updates counters, and raises alerts if thresholds are reached.
  * [UsageArchiveScheduler.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/service/UsageArchiveScheduler.java): Scheduled midnight task that archives Redis counts to MySQL history tables.

---

## 3. Step-by-Step Scenario Traces

### Scenario A: Dynamic Onboarding Flow
1. **Client Request**: A client registers with subdomain `"acme"` via `POST /api/tenants`.
2. **Transaction Start**: `TenantService.java` starts a database transaction.
3. **Database Creation**: The service calls `SchemaProvisioningService.java` to run:
   ```sql
   CREATE DATABASE IF NOT EXISTS tenant_acme;
   ```
4. **Flyway Migration Run**: The provisioning service starts a Flyway runner bound to the new schema:
   ```java
   Flyway flyway = Flyway.configure()
       .dataSource(dataSource)
       .schemas("tenant_acme")
       .locations("classpath:db/template")
       .load();
   flyway.migrate(); // Programmatic Flyway execution
   ```
5. **Outbox Log**: Write a `TENANT_CREATED` event log into the `saasify_master.outbox_log` database table.
6. **Commit**: The transaction commits in `saasify_master`.

### Scenario B: Request Quota Checking & Rating
1. **Request Intercept**: The Gateway receives `GET /api/users` with header `X-Tenant-ID: acme`.
2. **Redis Counter**: `QuotaCheckFilter.java` increments the key `quota:acme:yyyyMMdd` atomically.
3. **Evaluation**:
   * *Under Limits*: Request is routed downstream to `user-service`.
   * *Over Limits*: Routing stops. Returns HTTP 429 with reset timestamps:
     ```json
     {
       "error": "Too Many Requests",
       "message": "Daily API quota exceeded. Please upgrade plan.",
       "resetAt": "2026-06-23T00:00:00Z"
     }
     ```

### Scenario C: Session Eviction
1. **Tenant Suspended**: Admin updates status to `SUSPENDED` via `PUT /api/tenants/{id}/status?status=SUSPENDED`.
2. **Kafka Broadcast**: The outbox scheduler publishes a message containing tenant ID to the `tenant.suspended` Kafka topic.
3. **Redis Purge**: `TenantSuspendedConsumer.java` in `auth-service` reads the message and runs a scan:
   ```java
   String pattern = "refresh:" + tenantSubdomain + ":*";
   Set<String> keys = redisTemplate.keys(pattern);
   redisTemplate.delete(keys); // Removes all session tokens dynamically
   ```

---

## 4. Critical Code Walkthroughs

### 1. Dynamic Routing DataSource
In microservices like `user-service`, queries must route dynamically to the correct tenant database. This is managed by `MultiTenantRoutingDataSource`:

```java
package com.saasify.user.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MultiTenantRoutingDataSource extends AbstractRoutingDataSource {

    private final Map<Object, Object> targetDataSources = new ConcurrentHashMap<>();

    public MultiTenantRoutingDataSource(DataSource defaultTargetDataSource) {
        setDefaultTargetDataSource(defaultTargetDataSource);
        setTargetDataSources(targetDataSources);
    }

    @Override
    protected Object determineCurrentLookupKey() {
        // [ANNOTATION] Spring calls this method to retrieve the dynamic lookup key
        // representing the active database connection pool.
        return TenantContext.getCurrentTenant(); 
    }

    public void addTenantDataSource(String tenantId, DataSource dataSource) {
        // [ANNOTATION] Registers a new connection pool dynamically at runtime,
        // refreshes AbstractRoutingDataSource lookup lists, and avoids service restart.
        targetDataSources.put(tenantId, dataSource);
        setTargetDataSources(targetDataSources);
        afterPropertiesSet(); 
    }
}
```

### 2. Transactional Outbox Scheduling
To guarantee atomicity and avoid dual-write inconsistencies, outbox schedulers poll events from the database and publish them to Kafka:

```java
package com.saasify.tenant.service;

import com.saasify.tenant.model.OutboxEvent;
import com.saasify.tenant.repository.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class OutboxPublisherScheduler {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 5000) // Polls every 5 seconds
    @Transactional
    public void publishPendingEvents() {
        // [ANNOTATION] Pulls unprocessed event logs stored inside the database
        List<OutboxEvent> events = outboxRepository.findByProcessedFalse();

        for (OutboxEvent event : events) {
            String topic = resolveTopic(event.getType());
            
            // [ANNOTATION] Send message to Kafka. If broker is down, throws exception
            // rolling back the active database transaction to ensure exactly-once delivery.
            kafkaTemplate.send(topic, event.getPayload());

            event.setProcessed(true);
            outboxRepository.save(event);
        }
    }
}
```

---

## 5. Fault Tolerance & Fallback Matrix

SaaSify uses Resilience4j and Kafka retries to handle system failures:

| Trigger Scenario | Target Call | Protective Mechanism | Fallback Behavior |
| :--- | :--- | :--- | :--- |
| **Tenant Service Offline** | `user-service` calling `tenantServiceClient.getTenantDetails(tenantId)` | **Circuit Breaker & Retry** | Fall back to safe local thresholds (binds user limits to `FREE` defaults: Max 5 users). |
| **Kafka Broker Offline** | `api-gateway` recording request metrics | **Direct Buffer fallback** | Gateway logs metrics warnings locally and continues request routing without client-facing errors. |
| **MySQL Master Offline** | `billing-service` processing usage events | **Exponential Retry Pipeline** | Kafka retries the event 4 times with progressive delays (`2s -> 4s -> 8s`). If still offline, it routes to `usage.recorded.DLQ`. |
| **Redis Server Offline** | Gateway checking per-minute rate-limits | **Fail-Open Policy** | Rate limiting is bypassed, logging Redis warnings but allowing legitimate client traffic to proceed. |

---

## 6. Verification Runbook (Developer Cheat Sheet)

Developers can use the following commands to verify and test the platform's behaviors:

### A. Verify Schema Provisioning in MySQL
1. Connect to the MySQL Docker container:
   ```bash
   docker exec -it saasify-mysql mysql -u root -p
   ```
2. Run database checks:
   ```sql
   -- View all registered tenant databases
   SHOW DATABASES LIKE 'tenant_%';
   
   -- Verify table structure inside tenant sandbox
   USE tenant_acme;
   SHOW TABLES;
   SELECT * FROM users;
   ```

### B. Trigger User Registrations (via cURL)
1. Register a user inside tenant `"acme"`:
   ```bash
   curl -X POST http://localhost:8080/api/auth/register \
     -H "Content-Type: application/json" \
     -H "X-Tenant-ID: acme" \
     -d '{"email":"employee@acme.com","password":"Password123!","role":"MEMBER"}'
   ```

2. Login to retrieve the JWT:
   ```bash
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -H "X-Tenant-ID: acme" \
     -d '{"email":"employee@acme.com","password":"Password123!"}'
   ```

### C. Check Rate Limits (via Apache Benchmark)
To quickly test rate-limiting thresholds on the Gateway:
```bash
# Simulates 20 rapid calls against user endpoint
ab -n 20 -c 1 -H "X-Tenant-ID: acme" http://localhost:8080/api/users
```
*(Verify that calls 11-20 return HTTP 429).*

---

## 7. System Design Interview Q&A

### Q1: Can two users register with the same email address in SaaSify?
**Yes**, because their constraints are bound to separate schemas. Email constraints are evaluated within a tenant's database schema (`tenant_acme.users` vs `tenant_globex.users`). User registration does not check for duplicate emails across different schemas.

### Q2: Why use the transactional outbox pattern instead of publishing to Kafka directly from the controller?
Publishing directly from a controller introduces a **dual-write problem**. If the database transaction fails and rolls back after the Kafka message is published, the event is sent, but the database records do not exist. Using the outbox pattern ensures that database updates and events are atomic.

### Q3: What happens if `tenant-service` is down when a user registration occurs in `user-service`?
`user-service` is protected by a Resilience4j Circuit Breaker. If `tenant-service` is offline, the Feign client call to get tenant plan details fails. The fallback method intercepts the error and returns a default `FREE` subscription tier configurations, allowing the user registration to proceed safely.

### Q4: How does SaaSify prevent ThreadLocal memory leaks in Tomcat's thread pool?
Tomcat reuses threads across requests. If a tenant ID remains bound to a thread context after a request completes, the next request reusing that thread could execute queries against the wrong tenant database. SaaSify uses a servlet filter to clear the context inside a `finally` block:
```java
try {
    TenantContext.setCurrentTenant(tenantId);
    chain.doFilter(request, response);
} finally {
    TenantContext.clear(); // Prevents context leaks
}
```

---

## 8. Local Setup & Startup Sequence

### Step 1: Spin Up Backing Infrastructure
Run Docker Compose in the root folder to start MySQL, Redis, Kafka, and the observability stack:
```bash
docker compose up -d
```

### Step 2: Build the Codebase
Build and compile all Maven microservice modules:
```bash
mvn clean install -DskipTests
```

### Step 3: Start the Microservices
Start the services in the following order:
1. **eureka-server** (Port `8761`)
2. **api-gateway** (Port `8080`)
3. **tenant-service** (Port `8081`)
4. **auth-service** (Port `8082`)
5. **user-service** (Port `8083`)
6. **billing-service** (Port `8084`)
