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

# Package only the eureka-server module
RUN mvn -pl eureka-server -am clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /build/eureka-server/target/*.jar app.jar

EXPOSE 8761
ENTRYPOINT ["java", "-jar", "app.jar"]
