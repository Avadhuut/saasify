package com.saasify.billing.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UsageMetricsTest {

    private UsageMetrics usageMetrics;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private DataSource masterDataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private MeterRegistry meterRegistry;

    @BeforeEach
    public void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        usageMetrics = new UsageMetrics(redisTemplate, masterDataSource, meterRegistry);
    }

    @Test
    public void testUpdateUsageGauges_UpdatesMultiGaugeCorrectly() throws Exception {
        // Mock DB connection and queries
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        // Define 2 active tenants
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("id-1", "id-2");
        when(resultSet.getString("subdomain")).thenReturn("acme", "globex");
        when(resultSet.getString("plan")).thenReturn("FREE", "PRO");

        // Mock Redis value lookup
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(contains("acme"))).thenReturn("50");
        when(valueOperations.get(contains("globex"))).thenReturn("250");

        usageMetrics.updateUsageGauges();

        // Query simple registry to assert gauge values updated
        double acmeValue = meterRegistry.get("saasify_api_usage_current")
                .tag("tenant", "acme")
                .tag("plan", "FREE")
                .gauge().value();
        
        double globexValue = meterRegistry.get("saasify_api_usage_current")
                .tag("tenant", "globex")
                .tag("plan", "PRO")
                .gauge().value();

        assertEquals(50.0, acmeValue);
        assertEquals(250.0, globexValue);
    }
}
