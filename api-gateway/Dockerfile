# Stage 1: Compilation
FROM maven:3.8.5-openjdk-17-slim AS build
WORKDIR /build

# Copy root pom.xml and all modules to resolve parent dependencies
COPY pom.xml /build/
COPY eureka-server /build/eureka-server
COPY api-gateway /build/api-gateway
COPY tenant-service /build/tenant-service
COPY auth-service /build/auth-service
COPY user-service /build/user-service
COPY billing-service /build/billing-service

# Package only the api-gateway module
RUN mvn -pl api-gateway -am clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/api-gateway/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
