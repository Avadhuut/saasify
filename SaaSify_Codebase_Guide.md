# SaaSify — Master Codebase & Layer Connectivity Guide

This document provides a file-by-file directory, layer dependency map, and cross-service flow guide of the entire SaaSify repository. It is designed to help developers learn and understand the project end-to-end.

---

## 🗺️ Cross-Service Flow & Communication Map

Before diving into individual files, here is how the architectural layers communicate during a standard transaction:

```
[Client App] 
     │ (HTTP Request with X-Tenant-ID / Subdomain / Bearer JWT)
     ▼
[api-gateway]
     ├── RateLimitFilter ────(Increments per-minute window counter in Redis)
     ├── QuotaCheckFilter ───(Reads daily usage counter in Redis; blocks if suspended or over limit)
     ├── JwtValidationFilter ─(Validates JWT signature, claims, and matches X-Tenant-ID)
     └── ObservabilityFilter ─(Injects traceId/tenantId into MDC & OTel span attributes)
     │ (Routes downstream via Eureka Service Discovery)
     ▼
[auth-service] / [user-service] / [tenant-service]
     ├── MdcPropagationFilter ──(Extracts incoming W3C header context and injects into MDC)
     ├── TenantInterceptor ────(Extracts X-Tenant-ID and configures TenantContext ThreadLocal)
     ├── Controller ───────────(Accepts incoming request, maps request to DTOs)
     ├── Service ──────────────(Executes transactional business logic)
     │     ├── JPA Repository ───(MultiTenantRoutingDataSource switches pool to tenant schema)
     │     └── Feign Client ──────(Makes synchronous inter-service checks, e.g. User calls Tenant)
     │
     └── Kafka Broker ─────────(Outbox scheduler publishes events to Kafka asynchronously)
```

---

## 1. eureka-server

* **Purpose**: Serves as the central Service Registry. All core microservices register their host/port combinations with Eureka dynamically on startup. This allows the API Gateway to route traffic to active service nodes using logical service IDs (e.g. `lb://user-service`) instead of hardcoded IP addresses.

### File Roles:
* **[EurekaServerApplication.java](file:///c:/Users/Admin/Desktop/saasify/eureka-server/src/main/java/com/saasify/eureka/EurekaServerApplication.java)**: The bootloader class annotated with `@EnableEurekaServer`. It initializes the Spring Boot container and starts the discovery registry on port `8761`.

---

## 2. api-gateway

* **Purpose**: The intelligent proxy/gatekeeper for all incoming HTTP requests. It handles centralized rate limiting, daily request quotas, JWT verification, observability span attributes, and log correlation.

### File Roles:
* **[ApiGatewayApplication.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/ApiGatewayApplication.java)**: Configures the main entrypoint and registers dynamic routing tables matching paths to services.
* **[JwtValidationFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/JwtValidationFilter.java)**: Reactive Gateway filter checking JWT signatures, validating token expirations, and ensuring that the `tenantId` claim inside the token matches the requested `X-Tenant-ID` header.
* **[ObservabilityFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/ObservabilityFilter.java)**: Injects trace identifiers and the resolved tenant ID into the Micrometer/OpenTelemetry logging scope and tracing spans.
* **[QuotaCheckFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/QuotaCheckFilter.java)**: Enforces daily request limits. It reads plan configurations from the cached tenant details in Redis, increments the daily usage counter in Redis, blocks suspended tenants with HTTP 403, and blocks limit-exceeded tenants with HTTP 429 (adding the daily reset UTC midnight timestamp `resetAt` parameter).
* **[RateLimitFilter.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/filter/RateLimitFilter.java)**: Reactive filter implementing sliding-window rate limiting in Redis per tenant (e.g., 10 req/min for FREE plans). If exceeded, returns HTTP 429 with the `Retry-After` header.
* **[TenantMetrics.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/metrics/TenantMetrics.java)**: Interface defining metric tracking contracts (recording request counts, latency, and status codes by tenant).
* **[TenantMetricsImpl.java](file:///c:/Users/Admin/Desktop/saasify/api-gateway/src/main/java/com/saasify/gateway/metrics/TenantMetricsImpl.java)**: Implementation of `TenantMetrics` that binds custom Micrometer gauges, counters, and timers to the Prometheus meter registry.

---

## 3. tenant-service

* **Purpose**: Manages tenant metadata, master plans, schema onboarding provisioning, and status changes. Connects strictly to the platform administrative schema `saasify_master`.

### File-by-File Details:

#### **Config Layer**:
* **[DataSourceConfig.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/config/DataSourceConfig.java)**: Registers the database connection pool configuration pointing to the `saasify_master` database.
* **[MultiTenantRoutingDataSource.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/config/MultiTenantRoutingDataSource.java)**: Inherited router mapping lookup requests to active database pools.
* **[TenantContext.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/config/TenantContext.java)**: Utility storing active tenant IDs on the running thread context (`ThreadLocal`).

#### **Controllers & DTOs**:
* **[TenantController.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/controller/TenantController.java)**: REST controller exposing mappings to create tenants (`POST /api/tenants`), query details, update plans, and suspend accounts.
* **[CreateTenantRequest.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/dto/CreateTenantRequest.java)**: DTO containing data parameters (name, subdomain, plan, email) for onboarding.
* **[TenantResponse.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/dto/TenantResponse.java)**: DTO output format for tenant details returned to clients.

#### **Entities & Repositories**:
* **[Tenant.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/model/Tenant.java)**: JPA Entity mapped to `saasify_master.tenants`.
* **[OutboxEvent.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/model/OutboxEvent.java)**: JPA Entity mapped to the `saasify_master.outbox_log` database table (supporting transactional outbox event publishing).
* **[TenantRepository.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/repository/TenantRepository.java)**: Data accessor repository for tenants.
* **[OutboxRepository.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/repository/OutboxRepository.java)**: Data accessor repository for unsent outbox logs.

#### **Filters & Services**:
* **[MdcPropagationFilter.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/filter/MdcPropagationFilter.java)**: Synchronous servlet filter extracting tracing headers from the Gateway and binding them to the log context (MDC).
* **[SchemaProvisioningService.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/SchemaProvisioningService.java)**: Dynamic DDL execution service. It runs raw queries to create a new database schema on signup and initializes structural tables using isolated Flyway migrations.
* **[TenantService.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/TenantService.java)**: Implements lifecycle operations (creation, plan updates, suspension, deletion). Saves updates to the outbox database table inside the transaction context to ensure atomic execution.
* **[OutboxPublisherScheduler.java](file:///c:/Users/Admin/Desktop/saasify/tenant-service/src/main/java/com/saasify/tenant/service/OutboxPublisherScheduler.java)**: Background polling task that reads pending outbox logs from `saasify_master.outbox_log`, publishes corresponding messages to Kafka (`tenant.created`, `tenant.suspended`, `tenant.plan.upgraded`), and flags them as processed.

---

## 4. auth-service

* **Purpose**: Coordinates user registration, password verification, and JWT creation. Routes requests to individual tenant databases dynamically so that users belong exclusively to their tenant's schema.

### File-by-File Details:

#### **Config & Context**:
* **[MultiTenantDataSourceConfig.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/config/MultiTenantDataSourceConfig.java)**: Dynamically constructs Hibernate and JPA connection properties linked to the routing DataSource.
* **[MultiTenantRoutingDataSource.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/config/MultiTenantRoutingDataSource.java)**: Dynamic schema router for `auth-service`.
* **[TenantContext.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/config/TenantContext.java)**: ThreadLocal helper context.
* **[TenantInterceptor.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/config/TenantInterceptor.java)**: Intercepts incoming requests, extracts the `X-Tenant-ID` header, and registers it to `TenantContext` before execution.
* **[SecurityConfig.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/config/SecurityConfig.java)**: Registers BCrypt encryption beans and configures Spring Security to permit all public authentications.

#### **Controllers & DTOs**:
* **[AuthController.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/controller/AuthController.java)**: Controller exposing JWT authentication endpoints `/api/auth/register` and `/api/auth/login`.
* **[LoginRequest.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/dto/LoginRequest.java)**: Input DTO containing user credentials.
* **[RegisterRequest.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/dto/RegisterRequest.java)**: Input DTO to create new logins (email, password, role).
* **[AuthResponse.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/dto/AuthResponse.java)**: Output DTO containing token hashes and expiration durations.

#### **Entities & Repositories**:
* **[User.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/entity/User.java)**: Mapped domain model pointing to the tenant-isolated database table `tenant_{id}.users`.
* **[UserRepository.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/repository/UserRepository.java)**: Accessor mapping JPA queries to the dynamically routed database tables.

#### **Core Services & Kafka Consumers**:
* **[AuthService.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/service/AuthService.java)**: Orchestrates registrations (saving encrypted credentials) and logins (authenticating credentials and returning generated JWTs).
* **[JwtUtil.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/security/JwtUtil.java)**: Cryptographic component packaging tenant claims and verifying tokens.
* **[TenantSuspendedConsumer.java](file:///c:/Users/Admin/Desktop/saasify/auth-service/src/main/java/com/saasify/auth/kafka/TenantSuspendedConsumer.java)**: Kafka consumer listening to the `tenant.suspended` topic. On suspension, it evicts all active session refresh tokens from Redis for the tenant, forcing instant user logout.

---

## 5. user-service

* **Purpose**: Exposes REST interfaces to query and update user details inside the isolated tenant schemas, communicating with `tenant-service` to assert user limits.

### File-by-File Details:

#### **Config Layer**:
* **[FeignConfig.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/config/FeignConfig.java)**: Configures standard Feign interceptors to propagate tracing and tenant headers (`X-Tenant-ID`) during downstream service calls.
* **[SecurityConfig.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/config/SecurityConfig.java)**: Configures a stateless security filter chain and exposes the `BCryptPasswordEncoder` bean to align user creation password hashing with `auth-service`.
* **[MultiTenantRoutingDataSource.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/config/MultiTenantRoutingDataSource.java)**: Schema routing datasource.
* **[TenantInterceptor.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/config/TenantInterceptor.java)**: Extracts incoming headers and binds them to `TenantContext` before execution.

#### **Controllers, DTOs & Exceptions**:
* **[UserController.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/controller/UserController.java)**: Controller exposing protected endpoints: `GET /api/users` (list users), `POST /api/users` (create user), and `PUT /api/users/{id}` (modify role).
* **[CreateUserRequest.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/dto/CreateUserRequest.java)**: DTO carrying payload parameters for new users.
* **[UserResponse.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/dto/UserResponse.java)**: Mapped user parameters returned to clients.
* **[QuotaExceededException.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/exception/QuotaExceededException.java)**: Exception thrown when a tenant exceeds their plan's user limit.

#### **Entities, Repositories & Clients**:
* **[AppUser.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/entity/AppUser.java)**: Domain Entity representing `tenant_{id}.users`.
* **[UserRepository.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/repository/UserRepository.java)**: Data access layer.
* **[TenantServiceClient.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/feign/TenantServiceClient.java)**: Declarative Feign client communicating with `tenant-service`. Protected by Resilience4j circuit breakers and fallback thresholds.

#### **Services**:
* **[UserService.java](file:///c:/Users/Admin/Desktop/saasify/user-service/src/main/java/com/saasify/user/service/UserService.java)**: Executes business operations. Prior to creating a user, it calls `tenantServiceClient` to check the tenant's plan limits. If the user count exceeds the plan's threshold (e.g. 5 users for FREE plans), it throws a `QuotaExceededException`.

---

## 6. billing-service

* **Purpose**: Asynchronously aggregates API usage from the Gateway, tracks metrics, and archives daily metrics logs to MySQL.

### File-by-File Details:
* **[BillingController.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/controller/BillingController.java)**: REST endpoints allowing dashboards to query daily usage (`GET /api/billing/usage/{tenantId}`) and historical trends (`GET /api/billing/usage/{tenantId}/history`).
* **[TenantUsageHistory.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/entity/TenantUsageHistory.java)**: Domain model representing master tables tracking chronological daily usage details.
* **[UsageHistoryRepository.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/repository/UsageHistoryRepository.java)**: Data accessor interface.
* **[UsageEventConsumer.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/kafka/UsageEventConsumer.java)**: Kafka consumer listening to the `usage.recorded` topic. It increments the daily API counter in Redis and dispatches warnings to `quota.exceeded` if plan limits are hit.
* **[UsageMetrics.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/metrics/UsageMetrics.java)**: Live metrics aggregator. Regularly polls MySQL for active tenants and binds their current Redis daily usage counts to Prometheus metrics gauges.
* **[UsageArchiveScheduler.java](file:///c:/Users/Admin/Desktop/saasify/billing-service/src/main/java/com/saasify/billing/service/UsageArchiveScheduler.java)**: Daily cron task. Runs at UTC midnight to move Redis daily usage stats to the database history table and flush the Redis keys.
