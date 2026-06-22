package com.saasify.billing.controller;

import com.saasify.billing.entity.TenantUsageHistory;
import com.saasify.billing.repository.UsageHistoryRepository;
import com.saasify.billing.service.UsageArchiveScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BillingController.class)
public class BillingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RedisOperations<String, String> redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @MockBean
    private UsageHistoryRepository usageHistoryRepository;

    @MockBean
    private UsageArchiveScheduler usageArchiveScheduler;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private Connection connection;

    @MockBean
    private PreparedStatement preparedStatement;

    @MockBean
    private ResultSet resultSet;

    @Test
    public void testGetCurrentUsage_Success() throws Exception {
        // Mock DB lookup
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("tenant-uuid-123");
        when(resultSet.getString("subdomain")).thenReturn("acme");

        // Mock Redis lookup
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(contains("acme"))).thenReturn("75");

        mockMvc.perform(get("/api/billing/usage/acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.apiCalls").value(75));
    }

    @Test
    public void testGetCurrentUsage_ParsingException() throws Exception {
        // Mock DB lookup
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("tenant-uuid-123");
        when(resultSet.getString("subdomain")).thenReturn("acme");

        // Mock Redis lookup returning non-parseable value
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(contains("acme"))).thenReturn("invalid-long-value");

        mockMvc.perform(get("/api/billing/usage/acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.apiCalls").value(0));
    }

    @Test
    public void testGetUsageHistory_Success() throws Exception {
        // Mock DB lookup
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("tenant-uuid-123");
        when(resultSet.getString("subdomain")).thenReturn("acme");

        TenantUsageHistory history = TenantUsageHistory.builder()
                .id(UUID.randomUUID().toString())
                .tenantId("tenant-uuid-123")
                .date(LocalDate.now())
                .apiCalls(500L)
                .build();

        when(usageHistoryRepository.findByTenantIdOrderByDateDesc("tenant-uuid-123"))
                .thenReturn(Collections.singletonList(history));

        mockMvc.perform(get("/api/billing/usage/acme/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value("tenant-uuid-123"))
                .andExpect(jsonPath("$[0].apiCalls").value(500));
    }

    @Test
    public void testTriggerArchive_Success() throws Exception {
        doNothing().when(usageArchiveScheduler).archiveYesterdayUsage();

        mockMvc.perform(post("/api/billing/usage/trigger-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").exists());

        verify(usageArchiveScheduler, times(1)).archiveYesterdayUsage();
    }

    @Test
    public void testGetCurrentUsage_DatabaseException() throws Exception {
        // Mock DB lookup throwing exception
        when(dataSource.getConnection()).thenThrow(new java.sql.SQLException("Connection failed"));

        // Mock Redis lookup
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(contains("acme"))).thenReturn("75");

        mockMvc.perform(get("/api/billing/usage/acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("acme"))
                .andExpect(jsonPath("$.apiCalls").value(75));
    }
}
