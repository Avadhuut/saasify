package com.saasify.tenant.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasify.tenant.dto.CreateTenantRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.saasify.tenant.model.Tenant;
import com.saasify.tenant.model.OutboxEvent;
import com.saasify.tenant.repository.OutboxRepository;
import com.saasify.tenant.service.TenantService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.hamcrest.Matchers.is;

import java.util.List;

/**
 * Full integration test suite using Testcontainers to boot dynamic MySQL, Kafka, and Redis services.
 * Tests tenant onboarding API (POST /api/tenants), including dynamic schema provisioning and validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
public class TenantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TenantService tenantService;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("saasify_master")
            .withUsername("root")
            .withPassword("root_pass");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test
    public void testOnboardTenant_Success() throws Exception {
        CreateTenantRequest request = CreateTenantRequest.builder()
                .name("Acme Corp")
                .subdomain("acme")
                .plan("FREE")
                .contactEmail("admin@acme.com")
                .build();

        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Acme Corp")))
                .andExpect(jsonPath("$.subdomain", is("acme")))
                .andExpect(jsonPath("$.plan", is("FREE")))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    public void testSuspendTenant_WritesToOutbox() {
        // Onboard tenant first
        CreateTenantRequest request = CreateTenantRequest.builder()
                .name("Globex Corp")
                .subdomain("globex")
                .plan("FREE")
                .contactEmail("admin@globex.com")
                .build();
        Tenant tenant = tenantService.createTenant(request);

        // Suspend tenant
        tenantService.updateTenantStatus(tenant.getId(), "SUSPENDED");

        // Assert outbox event is created inside the master database outbox table
        List<OutboxEvent> events = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc();
        assertFalse(events.isEmpty());
        
        OutboxEvent event = events.stream()
                .filter(e -> e.getAggregateId().equals(tenant.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Outbox event not found for aggregate ID: " + tenant.getId()));
                
        assertEquals("Tenant", event.getAggregateType());
        assertEquals("TENANT_SUSPENDED", event.getEventType());
        assertEquals(tenant.getId(), event.getPayload());
    }
}

