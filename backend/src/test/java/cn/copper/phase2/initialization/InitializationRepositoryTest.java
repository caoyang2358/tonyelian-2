package cn.copper.phase2.initialization;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitializationRepositoryTest {
    @Test
    void readinessActuallyRunsSelectOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        assertTrue(new InitializationRepository(jdbc).connectionReady());
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void unavailableCountsAreNotInventedAsZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM dim_workshop", Long.class)).thenReturn(null);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> new InitializationRepository(jdbc).counts());
    }
}
