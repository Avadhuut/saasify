# Saasify: Multi-Tenant Schema-Isolated Microservices Framework

Saasify is a production-grade, highly scalable SaaS (Software-as-a-Service) boilerplate framework built with **Spring Boot**, **Spring Cloud**, **Apache Kafka**, **Redis**, and **MySQL**. It implements a **Schema-per-Tenant** architecture, providing absolute data isolation between different customer accounts (tenants) while utilizing shared computing resources.

---

## 🏗️ Architecture & Data Isolation Flow

The framework employs a dynamic database routing mechanism. Each tenant's data is housed in a separate, isolated schema inside MySQL, dynamically provisioned at registration. 

```mermaid
graph TD
    Client[Client App / Postman] -->|HTTP Request| GW[API Gateway :8080]
    GW -->|Service Discovery| Eureka[Eureka Registry :8761]
    
    subgraph Core Services
        GW -->|Route & Filter| Auth[Auth Service :8082]
        GW -->|Route & Filter| Tenant[Tenant Service :8081]
        GW -->|Route & Filter| User[User Service :8083]
        GW -->|Route & Filter| Billing[Billing Service :8084]
    end

    subgraph Data & Telemetry
        Tenant -->|Provision & Route| DB[(MySQL Master & Tenant Schemas :3307)]
        User -->|Dynamic Schema Query| DB
        
        GW -->|Publish 'usage.recorded'| Kafka[Kafka Broker :9092]
        Kafka -->|Consume Usage| Billing
        Billing -->|Atomic Counters & TTL| Redis[(Redis Cache :6379)]
        Billing -->|Daily Archive / tenants plan| DB
    end
    
    subgraph Observability
        GW -->|Metrics| Prom[Prometheus :9090]
        Prom --> Grafana[Grafana :3000]
        GW -->|Tracing| Zipkin[Zipkin :9411]
    end
```

### Key Architectural Pillars:
1. **Schema-per-Tenant Isolation**: Customers (tenants) dynamically resolve to their own dedicated database schema based on the `X-Tenant-ID` HTTP header or Host subdomain.
2. **Elastic Tenant Provisioning**: Onboarding a new tenant automatically runs DDL migration scripts (via Flyway) to generate a isolated database schema instantly without server restart.
3. **Decoupled Asynchronous Telemetry**: API Gateways track user request counts and dispatch them to Apache Kafka. The Billing Service consumes these events asynchronously, maintaining high throughput.
4. **Gateway-Level JWT Guardrails**: Centralized security filters validate tokens and block cross-tenant hijacking attempts.
5. **Gateway-Level Auto-Suspension**: Instantly blocks API access for `SUSPENDED` tenants returning `402 Payment Required`, with dynamic cache-refill fallbacks.
6. **Telemetry Resiliency (Kafka DLQ & Retry)**: Consumed telemetry events are automatically retried 4 times with exponential backoff, routing persistent failures to a Dead Letter Queue (DLQ) for monitoring.

---

## 🛠️ Technology Stack

| Component | Technology | Description |
| :--- | :--- | :--- |
| **Core Framework** | Java 17+, Spring Boot 3.x | Back-end microservices runtime. |
| **Gateway & Discovery** | Spring Cloud Gateway, Netflix Eureka | Central registry and intelligent request routing. |
| **Database** | MySQL 8.0, Flyway | Multitenant database storage with schema migrations. |
| **Telemetry Cache** | Redis 7.0 (Alpine) | Daily rate-limiting counters & distributed caches. |
| **Event Broker** | Apache Kafka 3.x (Confluent) | High-throughput asynchronous message pipeline. |
| **Observability** | Prometheus, Grafana, OpenTelemetry, Zipkin | Distributed tracing, performance tracking, and dashboards. |

---

## ⚙️ Prerequisites

Before running the application, make sure you have the following installed:
* [Java Development Kit (JDK) 17 or 23](https://adoptium.net/)
* [Apache Maven 3.8+](https://maven.apache.org/)
* [Docker Desktop](https://www.docker.com/products/docker-desktop/)

---

## 🚀 Getting Started

### 1. Launch Docker Infrastructure
Start the database, caching layer, event broker, and tracing components:
```bash
docker compose up -d
```
Verify all containers are up and healthy:
```bash
docker ps
```

### 2. Build the Microservices
Build and package all multi-module Maven projects (skipping tests for quick startup):
```bash
mvn clean install -Dmaven.test.skip=true
```

### 3. Start the Services
Use the bundled Windows batch script to launch the application:
```cmd
run-services.bat
```
*(When prompted to rebuild, type `n` if you have already run the Maven compile command above).*

The services will spin up in the following order:
* **Eureka Registry Server** (Port `8761`)
* **API Gateway** (Port `8080`)
* **Tenant Service** (Port `8081`)
* **Auth Service** (Port `8082`)
* **User Service** (Port `8083`)
* **Billing Service** (Port `8084`)

---

## 🧪 Postman Testing Walkthrough

Import the collection found inside `postman/Saasify Platform API E2E.postman_collection.json` into Postman.

### Step 1: Onboard a New Tenant
* **Endpoint**: `POST http://localhost:8080/api/tenants`
* **Request Payload**:
  ```json
  {
    "name": "Acme Corporation",
    "subdomain": "acme",
    "contactEmail": "admin@acme.com",
    "plan": "FREE"
  }
  ```
* **What happens**: The system creates a new entry in `saasify_master.tenants`, creates a dedicated database schema `tenant_acme`, and runs the user tables migration automatically.

### Step 2: Register a Tenant User
* **Endpoint**: `POST http://localhost:8080/api/auth/register`
* **Header**: `X-Tenant-ID: acme`
* **Request Payload**:
  ```json
  {
    "email": "employee@acme.com",
    "password": "Password123!",
    "role": "MEMBER"
  }
  ```
* **Duplicate Protection**: If this email is already registered under this tenant, the system returns `409 Conflict` with: `"You have already registered. Please try to log in."`.

### Step 3: Login to Obtain JWT Token
* **Endpoint**: `POST http://localhost:8080/api/auth/login`
* **Header**: `X-Tenant-ID: acme`
* **Request Payload**:
  ```json
  {
    "email": "employee@acme.com",
    "password": "Password123!"
  }
  ```
* **What happens**: Copies the returned `accessToken` JWT to use in subsequent requests.

### Step 4: Access User Services (Protected)
* **Endpoint**: `GET http://localhost:8080/api/users`
* **Headers**:
  * `Authorization`: `Bearer <paste_your_accessToken>`
  * `X-Tenant-ID`: `acme`

### Step 5: Billing & Usage Tracking
Make multiple API calls as the user, then query metrics:
1. **Get Today's Usage**: 
   * `GET http://localhost:8080/api/billing/usage/acme`
   * Returns live real-time API request counters queried from Redis.
2. **Trigger Archival (Simulates Daily Cron)**:
   * `POST http://localhost:8080/api/billing/usage/trigger-archive` (Protected endpoint - requires auth headers).
   * Moves yesterday's Redis telemetry counters to permanent database history.
3. **Get Usage History**:
   * `GET http://localhost:8080/api/billing/usage/acme/history`
   * Queries the database history catalog using the tenant's mapped UUID.

---

## 📤 Publishing to GitHub

To publish this project to your own GitHub repository, execute the following commands in the project's root folder:

### 1. Initialize Git Repository
```bash
git init
```

### 2. Configure Git Exclusions (`.gitignore`)
Ensure compiled files and target directories are ignored. A standard `.gitignore` is already set up in the workspace.

### 3. Stage and Commit Files
```bash
git add .
git commit -m "Initial commit: Saasify Multitenant Microservices Boilerplate"
```

### 4. Create a New Repository on GitHub
1. Go to your [GitHub Account](https://github.com/) and click **New Repository**.
2. Name it `saasify` (or choose another name).
3. Do **not** check "Initialize this repository with a README", "Add .gitignore", or "Choose a license" (as they are already present locally).
4. Click **Create repository**.

### 5. Link Local Repository and Push
Copy the commands from the GitHub quick setup page:
```bash
# Rename the default branch to main
git branch -M main

# Link your local repo to the remote repository (Replace with your actual GitHub URL)
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/saasify.git

# Push to the main branch
git push -u origin main
```
