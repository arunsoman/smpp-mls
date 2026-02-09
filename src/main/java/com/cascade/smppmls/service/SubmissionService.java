package com.cascade.smppmls.service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.model.QueuedMessage;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.util.IdGenerator;
import com.cascade.smppmls.util.MsisdnUtils;

/**
 * Submission service - writes ONLY to Redis (fast API response).
 * ClickHouse writes happen asynchronously via AsyncClickHouseBulkWriter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final RedisQueueService redisQueueService;
    private final OperatorRouter router;
    private final IdGenerator idGenerator;

    public SubmitResponse submit(SubmitRequest req) {
        String normalized = MsisdnUtils.normalizeToE164(req.getMsisdn(), "93");
        if (normalized == null) throw new IllegalArgumentException("Invalid msisdn");
        if (normalized.startsWith("+9374")) {
            throw new IllegalArgumentException("et msisdn");
        }

        String[] route = router.resolve(normalized);

        String operator = null;
        String sessionId = null;
        if (route != null) {
            operator = route[0];
            sessionId = route[1];
        }
        
        // Idempotency check (Redis cache - FAST)
        if (req.getClientMsgId() != null && !req.getClientMsgId().isBlank()) {
            Long cachedId = redisQueueService.getCachedMessageId(req.getClientMsgId());
            if (cachedId != null) {
                log.debug("Idempotency hit for clientMsgId={}, returning cached id={}", req.getClientMsgId(), cachedId);
                // Return cached response (message already queued)
                return new SubmitResponse(
                    UUID.randomUUID().toString(), 
                    String.valueOf(cachedId), 
                    "QUEUED", 
                    operator, 
                    sessionId
                );
            }
        }

        String requestId = UUID.randomUUID().toString();
        
        // Content-based signature for duplicate detection
        String signature = null;
        try {
            String raw = normalized + ":" + req.getMessage();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            signature = java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Error calculating signature: {}", e.getMessage());
        }

        // Generate unique message ID
        long messageId = idGenerator.nextId();
        long queuedAt = System.currentTimeMillis();
        
        // Create queued message
        QueuedMessage message = QueuedMessage.builder()
                .id(messageId)
                .requestId(requestId)
                .clientMsgId(req.getClientMsgId())
                .msisdn(normalized)
                .message(req.getMessage())
                .sourceAddr(req.getSourceAddr())
                .signature(signature)
                .priority(req.getPriority() != null ? req.getPriority() : "NORMAL")
                .operator(operator)
                .sessionId(sessionId)
                .status("QUEUED")
                .queuedAt(queuedAt)
                .build();

        // Write to Redis ONLY (fast - <2ms)
        // 1. Add to session queue
        redisQueueService.pushToQueue(sessionId, message);
        
        // 2. Add to pending ClickHouse insert (async bulk insert every 5s)
        redisQueueService.addToPendingInsert(message);
        
        // 3. Cache for idempotency (1 hour TTL)
        if (req.getClientMsgId() != null && !req.getClientMsgId().isBlank()) {
            redisQueueService.cacheIdempotency(req.getClientMsgId(), messageId, 1);
        }

        log.info("Queued message id={} requestId={} -> {} (operator={}, session={})", 
            messageId, requestId, normalized, operator, sessionId);

        return new SubmitResponse(requestId, String.valueOf(messageId), "QUEUED", operator, sessionId);
    }
}
