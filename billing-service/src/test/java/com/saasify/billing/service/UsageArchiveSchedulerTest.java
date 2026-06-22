package com.saasify.billing.service;

import com.saasify.billing.entity.TenantUsageHistory;
import com.saasify.billing.repository.UsageHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UsageArchiveSchedulerTest {

    @InjectMocks
    private UsageArchiveScheduler usageArchiveScheduler;

    @Mock
    private RedisOperations<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UsageHistoryRepository usageHistoryRepository;

    @Mock
    private DataSource masterDataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Test
    public void testArchiveYesterdayUsage_Success() throws Exception {
        // Mock DB connection and query results
        when(masterDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        
        // Mock two active tenants
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("tenant-id-1", "tenant-id-2");
        when(resultSet.getString("subdomain")).thenReturn("acme", "globex");

        // Mock Redis value lookups
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(contains("acme"))).thenReturn("150");
        when(valueOperations.get(contains("globex"))).thenReturn(null);
        when(valueOperations.get(contains("tenant-id-2"))).thenReturn("75"); // Fallback key lookup

        // Mock repository saving
        when(usageHistoryRepository.save(any(TenantUsageHistory.class))).thenReturn(new TenantUsageHistory());

        usageArchiveScheduler.archiveYesterdayUsage();

        // Verify save and delete commands were dispatched
        verify(usageHistoryRepository, times(2)).save(any(TenantUsageHistory.class));
        verify(redisTemplate, times(2)).delete(anyString());
    }

    @Test
    public void testArchiveYesterdayUsage_DatabaseException() throws Exception {
        when(masterDataSource.getConnection()).thenThrow(new java.sql.SQLException("Connection failed"));

        usageArchiveScheduler.archiveYesterdayUsage();

        verifyNoInteractions(usageHistoryRepository, redisTemplate);
    }
}
