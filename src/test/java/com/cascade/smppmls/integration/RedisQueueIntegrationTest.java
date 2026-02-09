package com.cascade.smppmls.integration;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.model.QueuedMessage;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.service.RedisQueueService;
import com.cascade.smppmls.service.SubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import redis.embedded.RedisServer;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.Mockito.when;


@SpringBootTest
@DirtiesContext
public class RedisQueueIntegrationTest {

    private static RedisServer redisServer;
    private static final int REDIS_PORT = 6399;

    @BeforeAll
    public static void startRedis() throws Exception {
        try {
            redisServer = new RedisServer(REDIS_PORT);
            redisServer.start();
        } catch (Exception e) {
            // If port already in use, fail gracefully or try another
            System.err.println("Failed to start embedded Redis: " + e.getMessage());
        }
    }

    @AfterAll
    public static void stopRedis() {
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception e) {
                System.err.println("Error stopping Redis: " + e.getMessage());
            }
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> REDIS_PORT);
        registry.add("REDIS_HOST", () -> "localhost");
        registry.add("REDIS_PORT", () -> REDIS_PORT);
    }

    @org.junit.jupiter.api.BeforeEach
    void assertCleanState() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }
    
    @Autowired
    private SubmissionService submissionService;
    
    @Autowired
    private RedisQueueService redisQueueService;
    
    @Autowired
    private StringRedisTemplate redisTemplate;
    
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OperatorRouter router;

    @MockBean
    private com.cascade.smppmls.service.AsyncClickHouseBulkWriter bulkWriter;

    @MockBean
    private com.cascade.smppmls.service.AsyncClickHouseUpdater bulkUpdater;

    @Autowired
    private com.cascade.smppmls.smpp.JsmppSessionManager jsmppSessionManager;

    @MockBean
    private com.cascade.smppmls.repository.SmsOutboundRepository outboundRepository;
    
    @MockBean
    private com.cascade.smppmls.repository.SmsDlrRepository dlrRepository;

    @Test
    void testSubmitAndQueueFlow() throws Exception {
        // Setup mock router
        when(router.resolve(anyString())).thenReturn(new String[]{"roshan", "roshan:sess1"});
        
        // 1. Submit message
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("93791234567");
        req.setMessage("Test message");
        req.setPriority("NORMAL");
        req.setClientMsgId("client-123");
        
        SubmitResponse resp = submissionService.submit(req);
        
        assertNotNull(resp.getMessageId(), "Message ID should not be null");
        assertEquals("QUEUED", resp.getStatus());
        
        // 2. Verify in Redis Queue
        String queueKey = "queue:roshan:sess1";
        Set<String> queued = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        assertNotNull(queued);
        assertEquals(1, queued.size(), "Should have 1 message in queue");
        
        String msgJson = queued.iterator().next();
        QueuedMessage msg = objectMapper.readValue(msgJson, QueuedMessage.class);
        assertEquals("Test message", msg.getMessage());
        assertEquals("client-123", msg.getClientMsgId());
        
        // 3. Verify in Pending ClickHouse Set
        Set<String> pending = redisTemplate.opsForSet().members("pending:clickhouse");
        assertNotNull(pending);
        assertTrue(pending.size() >= 1, "Should have pending message for ClickHouse");
        
        // 4. Verify Idempotency Cache
        Long cachedId = redisQueueService.getCachedMessageId("client-123");
        assertNotNull(cachedId, "Should have cached ID");
        assertEquals(Long.parseLong(resp.getMessageId()), cachedId);
        
        // 5. Test idempotency (submit again)
        SubmitResponse resp2 = submissionService.submit(req);
        assertEquals(resp.getMessageId(), resp2.getMessageId(), "Should return same ID for duplicate submission");
        
        // Queue size should still be 1 (logic checks cache first and returns, does not push to queue again)
        queued = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        assertEquals(1, queued.size(), "Queue size should remain 1");
    }

    @Test
    void testSessionSenderConsumption() throws Exception {
        // 1. push message directly to Redis
        String queueKey = "queue:test:sess1";
        QueuedMessage msg = new QueuedMessage();
        msg.setId(500L);
        msg.setMsisdn("93799999999");
        msg.setMessage("Test Consumption");
        msg.setPriority("NORMAL");
        msg.setQueuedAt(System.currentTimeMillis());
        msg.setStatus("QUEUED");
        
        redisTemplate.opsForZSet().add(queueKey, objectMapper.writeValueAsString(msg), msg.getQueuedAt());
        
        // 2. Mock SMPP session
        org.jsmpp.session.SMPPSession mockSession = org.mockito.Mockito.mock(org.jsmpp.session.SMPPSession.class);
        when(mockSession.getSessionState()).thenReturn(org.jsmpp.extra.SessionState.BOUND_TX);
        // Mock result object to avoid constructor issues
        org.jsmpp.session.SubmitSmResult mockResult = org.mockito.Mockito.mock(org.jsmpp.session.SubmitSmResult.class);
        when(mockResult.getMessageId()).thenReturn("msg-id-smsc");
        
        when(mockSession.submitShortMessage(
            any(), any(), any(), any(),
            any(), any(), any(),
            any(), anyByte(), anyByte(), any(), any(),
            any(), anyByte(), any(), anyByte(),
            any()
        )).thenReturn(mockResult);

        // 3. Create SessionSenderRedis
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        
        com.cascade.smppmls.smpp.SessionSenderRedis sender = new com.cascade.smppmls.smpp.SessionSenderRedis(
            "test:sess1", mockSession, "CMT", "1234", 
            10, 50, 
            redisQueueService, redisTemplate, objectMapper, executor, registry, null
        );
        
        // 4. Run sender tick
        sender.run();
        
        // Wait for async execution
        Thread.sleep(1000);
        
        // 5. Verify processed
        // Queue should be empty
        Set<String> queued = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        assertTrue(queued.isEmpty(), "Queue should be empty after consumption");
        
        // Update should be in pending:clickhouse:updates
        Set<String> updates = redisTemplate.opsForSet().members("pending:clickhouse:updates");
        assertNotNull(updates);
        assertFalse(updates.isEmpty(), "Should have pending update");
        
        String updateJson = updates.iterator().next();
        com.cascade.smppmls.model.UpdateRecord update = objectMapper.readValue(updateJson, com.cascade.smppmls.model.UpdateRecord.class);
        assertEquals(500L, update.getId());
        assertEquals("SENT", update.getStatus());
        assertEquals("msg-id-smsc", update.getSmscMsgId());
    }

    @Test
    void testStaleMessageCleanup() throws Exception {
        String queueKey = "queue:test:stale";
        long now = System.currentTimeMillis();
        long staleTime = now - 120000; // 2 minutes ago
        
        // 1. Add stale message
        QueuedMessage staleMsg = new QueuedMessage();
        staleMsg.setId(600L);
        staleMsg.setMsisdn("93700000001");
        staleMsg.setMessage("Stale");
        staleMsg.setPriority("NORMAL");
        staleMsg.setQueuedAt(staleTime);
        staleMsg.setStatus("QUEUED");
        
        redisTemplate.opsForZSet().add(queueKey, objectMapper.writeValueAsString(staleMsg), staleTime);
        
        // 2. Add fresh message
        QueuedMessage freshMsg = new QueuedMessage();
        freshMsg.setId(601L);
        freshMsg.setMsisdn("93700000002");
        freshMsg.setMessage("Fresh");
        freshMsg.setPriority("NORMAL");
        freshMsg.setQueuedAt(now);
        freshMsg.setStatus("QUEUED");
        
        redisTemplate.opsForZSet().add(queueKey, objectMapper.writeValueAsString(freshMsg), now);
        
        // 3. Run cleaner logic manually (simulating StaleMessageCleaner)
        // Ideally we would inject StaleMessageCleaner but here we test the logic
        long cutoff = now - 60000; // 1 minute
        Set<String> staleIds = redisQueueService.findStaleMessages("test:stale", cutoff);
        
        assertNotNull(staleIds);
        assertEquals(1, staleIds.size());
        
        long removed = redisQueueService.removeFromQueue("test:stale", staleIds);
        assertEquals(1, removed);
        
        // 4. Verify stale removed, fresh remains
        Set<String> queued = redisTemplate.opsForZSet().range(queueKey, 0, -1);
        assertEquals(1, queued.size());
        String remainingJson = queued.iterator().next();
        QueuedMessage remaining = objectMapper.readValue(remainingJson, QueuedMessage.class);
        assertEquals(601L, remaining.getId());
    }

    @Test
    void testAsyncClickHouseBulkWriterLogic() throws Exception {
        // 1. Add pending messages to Redis set
        QueuedMessage msg1 = new QueuedMessage();
        msg1.setId(701L);
        msg1.setMessage("Msg 1");
        
        QueuedMessage msg2 = new QueuedMessage();
        msg2.setId(702L);
        msg2.setMessage("Msg 2");
        
        redisQueueService.addToPendingInsert(msg1);
        redisQueueService.addToPendingInsert(msg2);
        
        Set<String> pending = redisTemplate.opsForSet().members("pending:clickhouse");
        // Verify size is 2 (mocks prevent async consumption)
        assertEquals(2, pending.size());
        
        // 2. Simulate writer popping logic
        redisTemplate.opsForSet().remove("pending:clickhouse", pending.toArray());
        
        // 3. Verify Redis set is empty
        pending = redisTemplate.opsForSet().members("pending:clickhouse");
        assertTrue(pending.isEmpty());
    }

    @Test
    void testEndToEndWithDlr() throws Exception {
        // 1. Submit message
        SubmitRequest req = new SubmitRequest();
        req.setMsisdn("93791234567");
        req.setMessage("E2E DLR Test");
        req.setPriority("NORMAL");
        req.setClientMsgId("e2e-dlr-1");
        
        // Mock routing
        when(router.resolve(anyString())).thenReturn(new String[]{"roshan", "roshan:sess1"});
        
        SubmitResponse resp = submissionService.submit(req);
        Long messageId = Long.parseLong(resp.getMessageId());
        
        // 2. Consume and Send (Simulated)
        String queueKey = "queue:roshan:sess1";
        // Verify queued
        assertEquals(1, redisTemplate.opsForZSet().size(queueKey));
        
        // Prepare mock session for sending
        org.jsmpp.session.SMPPSession mockSession = org.mockito.Mockito.mock(org.jsmpp.session.SMPPSession.class);
        when(mockSession.getSessionState()).thenReturn(org.jsmpp.extra.SessionState.BOUND_TX);
        org.jsmpp.session.SubmitSmResult mockResult = org.mockito.Mockito.mock(org.jsmpp.session.SubmitSmResult.class);
        when(mockResult.getMessageId()).thenReturn("smsc-msg-id-123");
        when(mockSession.submitShortMessage(
            any(), any(), any(), any(),
            any(), any(), any(),
            any(), anyByte(), anyByte(), any(), any(),
            any(), anyByte(), any(), anyByte(),
            any(byte[].class)
        )).thenReturn(mockResult);

        // Manually run consumer tick
        // Use a synchronous executor to ensure tasks run immediately
        java.util.concurrent.ExecutorService executor = new java.util.concurrent.AbstractExecutorService() {
            private boolean shutdown = false;
            
            @Override
            public void shutdown() { shutdown = true; }
            
            @Override
            public java.util.List<Runnable> shutdownNow() { 
                shutdown = true; 
                return java.util.Collections.emptyList(); 
            }
            
            @Override
            public boolean isShutdown() { return shutdown; }
            
            @Override
            public boolean isTerminated() { return shutdown; }
            
            @Override
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { 
                return true; 
            }
            
            @Override
            public void execute(Runnable command) {
                if (!shutdown) {
                    command.run(); // Execute synchronously
                }
            }
        };
        
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        
        com.cascade.smppmls.smpp.SessionSenderRedis sender = new com.cascade.smppmls.smpp.SessionSenderRedis(
             "roshan:sess1", mockSession, "CMT", "1234", 10, 50, 
             redisQueueService, redisTemplate, objectMapper, executor, registry, null
        );
        sender.run();
        
        // Verify queue empty
        assertEquals(0, redisTemplate.opsForZSet().size(queueKey));
        
        // Verify smsc_msg_id was cached in Redis
        String cachedId = redisTemplate.opsForValue().get("smsc:smsc-msg-id-123");
        assertNotNull(cachedId, "SMSC message ID should be cached in Redis");
        assertEquals(messageId.toString(), cachedId);
        
        // 3. Simulate DLR Reception
        // Mock repository finding the message
        com.cascade.smppmls.entity.SmsOutboundEntity entity = new com.cascade.smppmls.entity.SmsOutboundEntity();
        entity.setId(messageId);
        entity.setStatus("SENT");
        entity.setSmscMsgId("smsc-msg-id-123");
        
        when(outboundRepository.findBySmscMsgId("smsc-msg-id-123")).thenReturn(entity);
        
        // Create DeliverSm with receipted_message_id TLV (0x001E)
        org.jsmpp.bean.DeliverSm deliverSm = new org.jsmpp.bean.DeliverSm();
        deliverSm.setShortMessage("id:smsc-msg-id-123 stat:DELIVRD".getBytes());
        
        // Inject into manager
        jsmppSessionManager.onAcceptDeliverSm(deliverSm);
        
        // 4. Verify Status Update
        // New flow: Updates are pushed to pending:clickhouse:updates, NOT saved to H2 Repo immediately
        Set<String> updates = redisTemplate.opsForSet().members("pending:clickhouse:updates");
        assertNotNull(updates);
        assertFalse(updates.isEmpty(), "Should have pending update for DLR");
        
        boolean foundUpdate = false;
        for (String updateJson : updates) {
             com.cascade.smppmls.model.UpdateRecord update = objectMapper.readValue(updateJson, com.cascade.smppmls.model.UpdateRecord.class);
             if (update.getId().equals(messageId) && "DELIVERED".equals(update.getStatus())) {
                 foundUpdate = true;
                 break;
             }
        }
        assertTrue(foundUpdate, "Should find DELIVERED update in Redis");
        
        // Verify Repo was NOT called (optimization)
        org.mockito.Mockito.verify(outboundRepository, org.mockito.Mockito.never()).save(any());
    }
}
