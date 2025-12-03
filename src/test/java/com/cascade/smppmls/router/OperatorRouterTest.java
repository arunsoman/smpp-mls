package com.cascade.smppmls.router;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.cascade.smppmls.config.SmppProperties;

/**
 * Test cases for OperatorRouter with multi-bind support
 */
class OperatorRouterTest {

    private OperatorRouter router;
    private SmppProperties smppProperties;

    @BeforeEach
    void setUp() {
        smppProperties = new SmppProperties();
        router = new OperatorRouter(smppProperties);
    }

    @Test
    void testSingleSessionWithoutSessionCount() {
        // Given: Single session without session-count (defaults to 1)
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-79", "93-72"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        session.setUuId("roshan-primary");
        session.setSystemId("SHAHY");
        session.setPassword("SHAHY");
        session.setTps(100);
        // sessionCount not set, should default to 1
        
        operator.setSessions(List.of(session));
        operators.put("roshan", operator);
        smppProperties.setOperators(operators);
        
        // When
        router.init();
        
        // Then: Should create exactly 1 session
        List<String> sessions = router.getSessionsForOperator("roshan");
        assertEquals(1, sessions.size());
        assertEquals("roshan-primary", sessions.get(0));
    }

    @Test
    void testMultipleBindsWithSessionCount() {
        // Given: Session with session-count = 3
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-79", "93-72"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        session.setUuId("roshan-primary");
        session.setSystemId("SHAHY");
        session.setPassword("SHAHY");
        session.setTps(100);
        session.setSessionCount(3); // Multi-bind
        
        operator.setSessions(List.of(session));
        operators.put("roshan", operator);
        smppProperties.setOperators(operators);
        
        // When
        router.init();
        
        // Then: Should create 3 sessions with -1, -2, -3 suffixes
        List<String> sessions = router.getSessionsForOperator("roshan");
        assertEquals(3, sessions.size());
        assertTrue(sessions.contains("roshan-primary-1"));
        assertTrue(sessions.contains("roshan-primary-2"));
        assertTrue(sessions.contains("roshan-primary-3"));
    }

    @Test
    void testMultipleSessionsWithDifferentCounts() {
        // Given: Multiple sessions with different session counts
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-70", "93-71"));
        
        SmppProperties.Session session1 = new SmppProperties.Session();
        session1.setUuId("awcc-primary");
        session1.setSystemId("Shahy");
        session1.setSessionCount(5); // 5 binds
        
        SmppProperties.Session session2 = new SmppProperties.Session();
        session2.setUuId("awcc-backup");
        session2.setSystemId("Shahy-Backup");
        session2.setSessionCount(2); // 2 binds
        
        operator.setSessions(List.of(session1, session2));
        operators.put("awcc", operator);
        smppProperties.setOperators(operators);
        
        // When
        router.init();
        
        // Then: Should create 7 total sessions (5 + 2)
        List<String> sessions = router.getSessionsForOperator("awcc");
        assertEquals(7, sessions.size());
        
        // Verify primary sessions
        assertTrue(sessions.contains("awcc-primary-1"));
        assertTrue(sessions.contains("awcc-primary-2"));
        assertTrue(sessions.contains("awcc-primary-3"));
        assertTrue(sessions.contains("awcc-primary-4"));
        assertTrue(sessions.contains("awcc-primary-5"));
        
        // Verify backup sessions
        assertTrue(sessions.contains("awcc-backup-1"));
        assertTrue(sessions.contains("awcc-backup-2"));
    }

    @Test
    void testRoundRobinDistributionWithMultiBinds() {
        // Given: Operator with 3 sessions
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-77", "93-76"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        session.setUuId("mtn-primary");
        session.setSystemId("Shahy-EVD");
        session.setSessionCount(3);
        
        operator.setSessions(List.of(session));
        operators.put("mtn", operator);
        smppProperties.setOperators(operators);
        
        router.init();
        
        // When: Resolve multiple times
        String[] result1 = router.resolve("+93770000001");
        String[] result2 = router.resolve("+93770000002");
        String[] result3 = router.resolve("+93770000003");
        String[] result4 = router.resolve("+93770000004");
        
        // Then: Should round-robin through all 3 sessions
        assertNotNull(result1);
        assertNotNull(result2);
        assertNotNull(result3);
        assertNotNull(result4);
        
        assertEquals("mtn", result1[0]);
        assertEquals("mtn", result2[0]);
        assertEquals("mtn", result3[0]);
        assertEquals("mtn", result4[0]);
        
        // Session IDs should cycle through -1, -2, -3, -1
        assertEquals("mtn-primary-1", result1[1]);
        assertEquals("mtn-primary-2", result2[1]);
        assertEquals("mtn-primary-3", result3[1]);
        assertEquals("mtn-primary-1", result4[1]); // Back to first
    }

    @Test
    void testSessionWithoutUuIdUsesOperatorSystemId() {
        // Given: Session without uu-id
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-73"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        // No uu-id set
        session.setSystemId("EA_shahy");
        session.setSessionCount(2);
        
        operator.setSessions(List.of(session));
        operators.put("etisalat", operator);
        smppProperties.setOperators(operators);
        
        // When
        router.init();
        
        // Then: Should use operator:systemId as base
        List<String> sessions = router.getSessionsForOperator("etisalat");
        assertEquals(2, sessions.size());
        assertTrue(sessions.contains("etisalat:EA_shahy-1"));
        assertTrue(sessions.contains("etisalat:EA_shahy-2"));
    }

    @Test
    void testGetSessionsForNonExistentOperator() {
        // Given: Router initialized with one operator
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-79"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        session.setUuId("roshan-primary");
        session.setSessionCount(1);
        
        operator.setSessions(List.of(session));
        operators.put("roshan", operator);
        smppProperties.setOperators(operators);
        
        router.init();
        
        // When: Request sessions for non-existent operator
        List<String> sessions = router.getSessionsForOperator("nonexistent");
        
        // Then: Should return empty list
        assertNotNull(sessions);
        assertTrue(sessions.isEmpty());
    }

    @Test
    void testZeroSessionCountDefaultsToOne() {
        // Given: Session with session-count = 0
        Map<String, SmppProperties.Operator> operators = new HashMap<>();
        SmppProperties.Operator operator = new SmppProperties.Operator();
        operator.setPrefixes(List.of("93-79"));
        
        SmppProperties.Session session = new SmppProperties.Session();
        session.setUuId("test-session");
        session.setSessionCount(0); // Invalid, should default to 1
        
        operator.setSessions(List.of(session));
        operators.put("test", operator);
        smppProperties.setOperators(operators);
        
        // When
        router.init();
        
        // Then: Should create 1 session
        List<String> sessions = router.getSessionsForOperator("test");
        assertEquals(1, sessions.size());
        assertEquals("test-session", sessions.get(0));
    }
}
