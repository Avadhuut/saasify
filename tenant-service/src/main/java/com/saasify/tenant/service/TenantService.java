package com.saasify.tenant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasify.tenant.dto.CreateTenantRequest;
import com.saasify.tenant.model.Tenant;
import com.saasify.tenant.model.OutboxEvent;
import com.saasify.tenant.repository.TenantRepository;
import com.saasify.tenant.repository.OutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * Service class handling tenant onboarding workflows, database catalog writes,
 * dynamic system schema creation, and metadata caching.
 */
@Service
public class TenantService {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private SchemaProvisioningService schemaProvisioningService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Creates a new tenant within the platform.
     * Verifies that the subdomain is unique, records the tenant inside the administrative catalog,
     * provisions an isolated schema with database tables, and caches the tenant metadata.
     *
     * @param request the tenant details payload
     * @return the saved Tenant entity
     */
    @Transactional
    public Tenant createTenant(CreateTenantRequest request) {
        // 1. Verify subdomain is unique
        if (tenantRepository.existsBySubdomain(request.getSubdomain())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subdomain '" + request.getSubdomain() + "' is already in use.");
        }

        // 2. Build tenant entity structure
        String id = UUID.randomUUID().toString();
        String schemaName = "tenant_" + request.getSubdomain();

        Tenant tenant = Tenant.builder()
                .id(id)
                .name(request.getName())
                .subdomain(request.getSubdomain())
                .status("ACTIVE")
                .plan(request.getPlan())
                .schemaName(schemaName)
                .contactEmail(request.getContactEmail())
                .build();

        // 3. Save metadata record to the master database via JpaRepository
        Tenant savedTenant = tenantRepository.save(tenant);

        // 4. Invoke the provisioning service to create the schema and execute migrations
        schemaProvisioningService.provisionSchema(request.getSubdomain());

        // 5. Cache tenant metadata in Redis with a 5-minute Time-To-Live (TTL)
        cacheTenantMetadata(savedTenant);

        // Transactional Outbox Pattern: Log creation event in database transaction
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID().toString())
                .aggregateType("Tenant")
                .aggregateId(savedTenant.getId())
                .eventType("TENANT_CREATED")
                .payload(savedTenant.getId())
                .processed(false)
                .build();
        outboxRepository.save(outboxEvent);

        return savedTenant;
    }

    /**
     * Retrieves a tenant by their primary key.
     *
     * @param id the tenant UUID
     * @return the Tenant entity
     */
    public Tenant getTenantById(String idOrSubdomain) {
        return tenantRepository.findById(idOrSubdomain)
                .or(() -> tenantRepository.findBySubdomain(idOrSubdomain))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found with ID or Subdomain: " + idOrSubdomain));
    }

    /**
     * Retrieves all registered tenants.
     */
    public java.util.List<Tenant> getAllTenants() {
        return tenantRepository.findAll();
    }

    /**
     * Updates the subscription plan of a tenant.
     */
    @Transactional
    public Tenant updateTenantPlan(String id, String plan) {
        Tenant tenant = getTenantById(id);
        tenant.setPlan(plan);
        Tenant saved = tenantRepository.save(tenant);
        cacheTenantMetadata(saved);

        // Transactional Outbox Pattern: Log plan upgrade event in database transaction
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(UUID.randomUUID().toString())
                .aggregateType("Tenant")
                .aggregateId(id)
                .eventType("TENANT_PLAN_UPGRADED")
                .payload(id + ":" + plan)
                .processed(false)
                .build();
        outboxRepository.save(outboxEvent);

        return saved;
    }

    /**
     * Updates the status of a tenant. If suspended, dispatches an eviction event to Kafka.
     */
    @Transactional
    public Tenant updateTenantStatus(String id, String status) {
        Tenant tenant = getTenantById(id);
        tenant.setStatus(status);
        Tenant saved = tenantRepository.save(tenant);
        cacheTenantMetadata(saved);

        if ("SUSPENDED".equalsIgnoreCase(status)) {
            // Transactional Outbox Pattern: Log event to outbox within active DB transaction
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID().toString())
                    .aggregateType("Tenant")
                    .aggregateId(id)
                    .eventType("TENANT_SUSPENDED")
                    .payload(id)
                    .processed(false)
                    .build();
            outboxRepository.save(outboxEvent);
            System.out.println("Outbox: Logged suspension event in database transaction for tenant ID: " + id);
        }
        return saved;
    }

    /**
     * Performs a soft delete on a tenant, keeping data isolated but changing status to DELETED.
     */
    @Transactional
    public Tenant softDeleteTenant(String id) {
        Tenant tenant = getTenantById(id);
        tenant.setStatus("DELETED");
        Tenant saved = tenantRepository.save(tenant);
        
        // Remove from Redis cache
        String key = "tenant:" + tenant.getSubdomain();
        redisTemplate.delete(key);
        
        return saved;
    }

    /**
     * Serializes the tenant entity to JSON and stores it in Redis.
     */
    private void cacheTenantMetadata(Tenant tenant) {
        String key = "tenant:" + tenant.getSubdomain();
        try {
            String jsonContent = objectMapper.writeValueAsString(tenant);
            redisTemplate.opsForValue().set(key, jsonContent, Duration.ofMinutes(5));
        } catch (Exception e) {
            System.err.println("Warning: Redis cache synchronization failed for key: " + key + ". Reason: " + e.getMessage());
        }
    }
}

