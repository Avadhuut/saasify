package com.saasify.billing.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UsageEventConsumerTest {

    @InjectMocks
    private UsageEventConsumer usageEventConsumer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private DataSource masterDataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Test
    public void testConsumeUsageEvent_NullTenantId_DoesNothing() {
        usageEventConsumer.consumeUsageEvent(null);
        verifyNoInteractions(redisTemplate, kafkaTemplate, masterDataSource);
    }

    @Test
    public void testConsumeUsageEvent_ValidUsageUnderLimit_IncrementsCounterOnly() throws Exception {
        String tenantId = "acme";
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(50L); // 50 is under FREE limit of 100
        
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("plan")).thenReturn("FREE");

        usageEventConsumer.consumeUsageEvent(tenantId);

        verify(valueOperations, times(1)).increment(startsWith("usage:acme:"));
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    public void testConsumeUsageEvent_FirstRequest_Sets48HourExpiry() throws Exception {
        String tenantId = "acme";
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L); // First request
        
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("plan")).thenReturn("FREE");

        usageEventConsumer.consumeUsageEvent(tenantId);

        verify(valueOperations, times(1)).increment(startsWith("usage:acme:"));
        verify(redisTemplate, times(1)).expire(anyString(), eq(Duration.ofHours(48)));
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    public void testConsumeUsageEvent_HitsLimit_PublishesQuotaExceededEvent() throws Exception {
        String tenantId = "acme";
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        // Let's mock the 100th call (which hits the FREE limit)
        when(valueOperations.increment(anyString())).thenReturn(100L);
        
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("plan")).thenReturn("FREE");

        usageEventConsumer.consumeUsageEvent(tenantId);

        verify(valueOperations, times(1)).increment(startsWith("usage:acme:"));
        verify(kafkaTemplate, times(1)).send(eq("quota.exceeded"), eq(tenantId));
    }
}
