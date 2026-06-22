# SaaSify — Architecture & Implementation Guide

This guide provides an end-to-end overview of the architecture, design choices, and implementation details of SaaSify, a production-grade multi-tenant SaaS platform built on Java 17, Spring Boot 3, and Spring Cloud.

---

## Group 1 — Multi-Tenancy Core

### 1. Schema-Per-Tenant Isolation
SaaSify implements a **Schema-Per-Tenant (Database Isolation)** architecture, which is the gold standard for high-security SaaS platforms.

```
       +---------------------------------------------+
       |             Spring Boot Application         |
       |  (routes queries via MultiTenantRoutingDS)  |
       +---------------------------------------------+
                              |
        +---------------------+---------------------+
        |                     |                     |
        v                     v                     v
+---------------+     +---------------+     +---------------+
| MySQL Schema  |     | MySQL Schema  |     | MySQL Schema  |
| saasify_master|     |  tenant_acme  |     | tenant_globex |
+---------------+     +---------------+     +---------------+
```

* **Row-Level Isolation vs. Schema Isolation**:
  * *Row-Level Isolation* uses a shared table containing a `tenant_id` column. If a developer forgets a `WHERE tenant_id = ?` clause in a single repository query, tenant data leaks. 
  * *Schema-per-tenant isolation* separates data into physical schemas (`tenant_acme`, `tenant_globex`). Cross-tenant leakage is structurally impossible because connection pools are pointing directly to the tenant's own database.
* **AbstractRoutingDataSource**: Spring's concrete implementation of `javax.sql.DataSource` that routes connections dynamically based on a lookup key.
* **TenantContext & ThreadLocal**: The platform extracts the tenant's identity early in the request lifecycle and saves it to a `ThreadLocal` wrapper (`TenantContext`). ThreadLocal guarantees that the tenant's ID remains bound to the executing thread throughout downstream service execution.

---

### 2. AbstractRoutingDataSource Configuration
The multi-tenant routing is orchestrated by `MultiTenantRoutingDataSource.java` in the microservices:

* **Implementation of `determineCurrentLookupKey()`**:
  ```java
  public class MultiTenantRoutingDataSource extends AbstractRoutingDataSource {
      @Override
      protected Object determineCurrentLookupKey() {
          return TenantContext.getCurrentTenantId(); // Returns "acme", "globex", etc.
      }
  }
  }
  ```
* **DataSource Map & Lifecycle**:
  * On startup, the catalog configures a default `master` DataSource mapping to the `saasify_master` schema (holding tenants, subscriptions, and metadata).
  * As HTTP requests flow, `determineCurrentLookupKey()` is invoked. If the returned key (e.g., `acme`) exists in the target DataSource map, Spring retrieves the corresponding tenant connection.
* **Dynamic DataSource Addition**:
  When a tenant registers, the system instantiates a new `HikariDataSource` pointing to the newly provisioned schema, registers it in the target map, and calls `afterPropertiesSet()` on the routing source to refresh the datasource registry.
* **ThreadLocal Cleanup**:
  ThreadLocal storage is bound to threads, which are reused in thread pools (e.g., Tomcat, WebFlux). To prevent tenant contamination, a filter ensures the context is cleared in a `finally` block:
  ```java
  try {
      TenantContext.setCurrentTenantId(tenantId);
      chain.doFilter(request, response);
  } finally {
      TenantContext.clear(); // Prevents memory leaks and security pollution
  }
  ```

---

### 3. Dynamic Flyway Provisioning
Upon tenant registration in `tenant-service`, database schemas are provisioned programmatically:

```
Tenant Signup -> Create MySQL Schema -> Run Flyway Template Migrations -> Register DB Pool
```

* **Creating Schemas on the Fly**:
  The system runs a raw statement via JDBC:
  ```sql
  CREATE DATABASE IF NOT EXISTS tenant_acme;
  ```
* **Programmatic Flyway Migration**:
  Rather than running static startup migrations, `SchemaProvisioningService.java` configures an isolated Flyway instance programmatically:
  ```java
  Flyway flyway = Flyway.configure()
      .dataSource(masterDataSource)
      .schemas("tenant_acme")
      .locations("classpath:db/template") // Points to V1__create_tenant_tables.sql
      .load();
  flyway.migrate();
  ```
* **V1__create_tenant_tables.sql**: Establishes tenant-scoped tables (`users`, `orders`, `audit_log`) in the newly created schema.
* **Rollback on Failure**: If schema execution or metadata registration fails, the service catches the exception, triggers a `DROP DATABASE tenant_acme` query to avoid half-provisioned artifacts, and rolls back the master transaction.

---

### 4. Tenant Resolution Strategy
The API Gateway and downstream microservices determine which tenant context is active using three prioritizations:

1. **Header-Based Resolution**: Checks for the explicit `X-Tenant-ID` header.
2. **Subdomain-Based Resolution**: Parses the request host (e.g., `acme.saasify.com` -> `acme`).
3. **JWT-Claim Resolution**: Decodes validated JWT tokens, extracting the `tenantId` claim.

* **TenantResolutionFilter**:
  An API Gateway filter processes these priorities. If a tenant cannot be determined (e.g. public endpoints like `/api/auth/login` don't require one, but protected resources do), the filter blocks processing and returns a JSON payload containing the error `TENANT_NOT_RESOLVED` and HTTP Status 400.

---

## Group 2 — Quota and Billing

### 5. Subscription Quota Enforcement
Requests are measured against subscription tiers configured in the master schema:

| Tier | API Limit / Day | Max Users |
| :--- | :--- | :--- |
| **FREE** | 100 calls | 5 |
| **PRO** | 10,000 calls | 50 |
| **ENTERPRISE** | Unlimited | Unlimited |

* **Redis Counter Mechanics**:
  For every routed request, the Gateway increments a Redis counter atomically:
  ```java
  String key = "quota:" + tenantId + ":" + LocalDate.now().toString();
  Long count = redisTemplate.opsForValue().increment(key);
  ```
* **Gateway Check**:
  If the incremented count exceeds the plan limits cached in Redis, `QuotaCheckFilter.java` stops the request and returns an HTTP 429 payload containing details on the active plan, the upgrade URL, and a midnight `resetAt` UTC timestamp.
* **Midnight Reset & Archival Job**:
  At `00:00:00 UTC`, `UsageArchiveScheduler.java` runs a scheduled task:
  1. Queries active tenants from `saasify_master`.
  2. Pulls the yesterday metric from Redis (`quota:acme:2026-06-21`).
  3. Writes the usage history row to the master database `tenant_usage_history` table.
  4. Deletes the Redis usage key to prevent memory bloat.

---

### 6. Session Invalidation on Suspension
When a tenant fails to pay, their account status is set to `SUSPENDED` in `tenant-service`. The system triggers session invalidation across all services:

```
Tenant Suspended -> Kafka Message (tenant.suspended) -> Auth Service -> Evict Redis Session Keys
```

* **Kafka Message Dispatch**:
  A transactional outbox log generates a message to the `tenant.suspended` topic.
* **Session Invalidation Consumer**:
  The `TenantSuspendedConsumer` in `auth-service` receives the event.
* **Redis Key Scan and Purge**:
  Using the tenant identifier (UUID or subdomain), the consumer scans Redis for all keys matching:
  ```text
  refresh:acme:*
  ```
  It evicts them in bulk using `redisTemplate.delete(keys)`. Because the API Gateway validates authentication against these Redis refresh tokens on every handshake, all users of the suspended tenant are instantly logged out of the platform.

---

## Group 3 — Observability

### 7. OpenTelemetry and Zipkin
Trace context propagation is critical in microservice environments to trace requests from Gateway to database.

* **Trace Propagation**:
  W3C Trace Context headers (`traceparent`) are automatically injected by OpenTelemetry into HTTP clients (Feign, WebClient) and Kafka messages.
* **Span Customization**:
  The system appends the custom tag `tenant.id` to every OpenTelemetry tracer span:
  ```java
  span.setAttribute("tenant.id", TenantContext.getCurrentTenantId());
  ```
  This allows developers to query Zipkin for spans belonging to a specific tenant.
* **MDC Log Correlation**:
  Logs are tagged with tracing metadata via the Logback pattern configuration:
  ```text
  %d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId}, %X{spanId}, %X{tenantId}] %-5level %logger{36} - %msg%n
  ```
  This binds tracing and tenant contexts to every log output.

---

### 8. Prometheus Metrics and Grafana Dashboard
Performance indicators (KPIs) are tracked per tenant using custom metrics:

* **Custom Micrometer Metrics**:
  The Gateway registers tenant-aware metric instrumentation:
  * `saasify_api_requests_total`: Counter tracking total requests (tagged by `tenant`, `plan`, `route`, `status`).
  * `saasify_api_latency_seconds`: Histogram logging response durations (tagged by `tenant`).
  * `saasify_api_usage_current`: Gauges recording daily quota consumption per tenant.
* **Grafana Visualization**:
  PromQL queries query metric gauges using filters:
  ```promql
  sum(rate(saasify_api_requests_total{tenant="$tenant"}[5m]))
  ```
  This allows the Grafana dashboard to filter charts dynamically using a dropdown variable representing active tenants.

---

## Group 4 — Infrastructure

### 9. Kafka Event Pipeline
SaaSify uses Apache Kafka for asynchronous communication, decoupled workflows, and resilient integration.

* **Topic Topology**:
  * `tenant.created`: Dispatched when a tenant registers, triggering provisioning logic in downstream systems.
  * `tenant.suspended`: Dispatched when a tenant is suspended, triggering session evictions.
  * `tenant.plan.upgraded`: Dispatched when a tenant upgrades, updating Redis plan caches.
  * `usage.recorded`: Dispatched when Gateway registers usage to downstream billing.
  * `quota.exceeded`: Warning event when a tenant reaches 100% usage.
* **Transactional Outbox Pattern**:
  To guarantee that database updates and Kafka messages are atomic, services write events to an `outbox_log` table within the active database transaction. A separate scheduler thread (`OutboxPublisherScheduler.java`) polls this table, publishes messages to Kafka, and marks them as processed, preventing data loss.
* **Error Resilience & DLQs**:
  Consumers handle failures using Spring Kafka's `@RetryableTopic` annotation:
  ```java
  @RetryableTopic(
      attempts = "3",
      backoff = @Backoff(delay = 2000, multiplier = 2.0),
      dltTopicSuffix = ".DLQ"
  )
  ```
  If processing fails all three attempts (with exponential delays), the event routes to the dead-letter topic (e.g. `tenant.suspended.DLQ`) for manual intervention, preventing pipeline deadlocks.

---

### 10. CI/CD Automated Pipeline
SaaSify implements a four-stage GitHub Actions workflow to ensure deployment quality:

1. **Stage 1 — Build and Test**: Runs the Maven compiler and executes standard JUnit 5 test suites.
2. **Stage 2 — Coverage Verification**: Executes the `jacoco-maven-plugin` validation check, verifying that overall code instruction coverage is above **80%**. If coverage falls below 80%, the build fails.
3. **Stage 3 — Docker Build**: Compiles Docker images for each of the microservices (`eureka-server`, `api-gateway`, `tenant-service`, `auth-service`, `user-service`, `billing-service`) and publishes them to the GitHub Container Registry (GHCR).
4. **Stage 4 — E2E Integration Checks**: Boots the microservices and backing services using the composite `docker-compose-e2e.yml` file, runs health checks to ensure the stack is responsive, and executes the Newman Postman collection (`postman/saasify.postman_collection.json`) to verify endpoint routing, authentication, and rate limiting.
