package com.cascade.smppmls.service;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.util.MsisdnUtils;

@Slf4j
@Service
public class SubmissionService {

    private final SmsOutboundRepository outboundRepository;
    private final OperatorRouter router;

    /**
     * Time-windowed dedup cache: signature -> timestamp of first seen.
     * Uses ConcurrentHashMap.putIfAbsent() which is atomic — only one thread
     * wins the insert for a given key, all others see the existing timestamp.
     */
    private final ConcurrentHashMap<String, Instant> recentSignatures = new ConcurrentHashMap<>();

    /** Dedup window in minutes (configurable via smpp.dedup.window-minutes, default 5) */
    @Value("${smpp.dedup.window-minutes:5}")
    private int dedupWindowMinutes;

    /** Regex to mask dynamic content (e.g. timestamps/IDs) for fuzzy dedup. Optional. */
    @Value("${smpp.dedup.normalization-regex:}")
    private String dedupNormalizationRegex;

    public SubmissionService(SmsOutboundRepository outboundRepository, OperatorRouter router) {
        this.outboundRepository = outboundRepository;
        this.router = router;
    }

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

        // ── Check 1: clientMsgId dedup (if provided) ──
        if (req.getClientMsgId() != null && !req.getClientMsgId().isBlank()) {
            try {
                java.util.List<SmsOutboundEntity> existingList = outboundRepository.findByClientMsgId(req.getClientMsgId());
                if (existingList != null && !existingList.isEmpty()) {
                    SmsOutboundEntity existing = existingList.get(0);
                    if (existingList.size() > 1) {
                        log.warn("Found {} duplicate entries for clientMsgId={}, using first (id={})",
                            existingList.size(), req.getClientMsgId(), existing.getId());
                    }
                    log.info("Duplicate clientMsgId={} -> returning existing id={}", req.getClientMsgId(), existing.getId());
                    return toResponse(existing);
                }
            } catch (Exception e) {
                log.error("Error checking clientMsgId={}: {}", req.getClientMsgId(), e.getMessage());
            }
        }

        // ── Check 2: Content+Dest hash dedup (time-windowed) ──
        String rawMessage = req.getMessage() != null ? req.getMessage().trim() : "";
        String normalizedForDedup = rawMessage;
        
        // Apply fuzzy normalization if configured
        if (dedupNormalizationRegex != null && !dedupNormalizationRegex.isEmpty()) {
            try {
                normalizedForDedup = rawMessage.replaceAll(dedupNormalizationRegex, "*");
            } catch (Exception e) {
                log.warn("Invalid dedup regex '{}': {}", dedupNormalizationRegex, e.getMessage());
            }
        }
        
        String signature = calculateSignature(normalized, normalizedForDedup);
        
        if (signature != null) {
            // LOGGING TO DEBUG DUPLICATES (Show raw vs normalized)
            log.info("DEDUP CHECK: msisdn={} sig={} raw='{}' norm='{}'", 
                normalized, signature.substring(0, 8), 
                rawMessage.length() > 50 ? rawMessage.substring(0, 50) + "..." : rawMessage,
                normalizedForDedup.length() > 50 ? normalizedForDedup.substring(0, 50) + "..." : normalizedForDedup);

            Instant now = Instant.now();
            Instant firstSeen = recentSignatures.putIfAbsent(signature, now);

            if (firstSeen != null) {
                // Signature exists in cache — check if within window
                Instant windowStart = now.minusSeconds(dedupWindowMinutes * 60L);
                if (firstSeen.isAfter(windowStart)) {
                    // DUPLICATE: same content+dest within the dedup window
                    log.warn("DEDUP BLOCKED: dest={} signature={} firstSeen={} ({}s ago)",
                        normalized, signature.substring(0, 12) + "...",
                        firstSeen, java.time.Duration.between(firstSeen, now).getSeconds());
                    // Try to find the original record for a proper response
                    try {
                        java.util.Optional<SmsOutboundEntity> original = outboundRepository.findBySignature(signature);
                        if (original.isPresent()) {
                            return toResponse(original.get());
                        }
                    } catch (Exception e) {
                        log.warn("Could not find original for signature: {}", e.getMessage());
                    }
                    // Return a minimal duplicate response if DB lookup fails
                    return new SubmitResponse("DUPLICATE", "DUPLICATE", "DUPLICATE", operator, sessionId);
                } else {
                    // Entry expired — update timestamp for new window
                    recentSignatures.put(signature, now);
                }
            }
            // else: putIfAbsent returned null => this thread won the insert, proceed
        }

        // ── Persist new message ──
        String requestId = UUID.randomUUID().toString();
        
        // Generate a random 16-bit reference number for concatenation (1 to 65535)
        int concatRefNum = new java.util.Random().nextInt(65535) + 1;
        
        // Determine if we need to force UCS2 based on request encoding
        boolean forceUcs2 = "UCS2".equalsIgnoreCase(req.getEncoding()) || "UNICODE".equalsIgnoreCase(req.getEncoding());
        
        java.util.List<com.cascade.smppmls.util.MessageSplitterUtil.MessagePart> parts = 
            com.cascade.smppmls.util.MessageSplitterUtil.splitMessage(rawMessage, forceUcs2, concatRefNum);
            
        SmsOutboundEntity firstSaved = null;
        
        for (com.cascade.smppmls.util.MessageSplitterUtil.MessagePart part : parts) {
            String partClientMsgId = req.getClientMsgId();
            if (partClientMsgId != null && !partClientMsgId.isBlank() && parts.size() > 1) {
                partClientMsgId = partClientMsgId + "_p" + part.partNo;
            }
            
            SmsOutboundEntity entity = SmsOutboundEntity.builder()
                    .requestId(requestId)
                    .clientMsgId(partClientMsgId)
                    .msisdn(normalized)
                    .message(part.text) // Store the chunk
                    .signature(signature)
                    .priority(req.getPriority())
                    .operator(operator)
                    .sessionId(sessionId)
                    .status("QUEUED")
                    .encoding(part.encoding)
                    .udh(part.udh != null ? com.cascade.smppmls.util.MessageSplitterUtil.bytesToHex(part.udh) : null)
                    .partNo(part.partNo)
                    .totalParts(part.totalParts)
                    .concatRefNum(part.totalParts > 1 ? part.concatRefNum : null)
                    .build();

            SmsOutboundEntity saved = outboundRepository.save(entity);
            if (firstSaved == null) {
                firstSaved = saved;
            }
            log.info("Persisted outbound message part {}/{} id={} requestId={} -> {} (operator={}, session={})",
                part.partNo, part.totalParts, saved.getId(), saved.getRequestId(), normalized, operator, sessionId);
        }

        String messageId = firstSaved != null && firstSaved.getId() != null ? String.valueOf(firstSaved.getId()) : requestId;
        return new SubmitResponse(requestId, messageId, "QUEUED", operator, sessionId);
    }

    // ── Helpers ──

    private String calculateSignature(String normalizedMsisdn, String message) {
        try {
            String raw = normalizedMsisdn + ":" + message;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Error calculating signature: {}", e.getMessage());
            return null;
        }
    }

    private SubmitResponse toResponse(SmsOutboundEntity existing) {
        if (existing.getRequestId() == null) {
            existing.setRequestId(UUID.randomUUID().toString());
            outboundRepository.save(existing);
        }
        String requestId = existing.getRequestId();
        String messageId = existing.getSmscMsgId() != null
            ? existing.getSmscMsgId()
            : (existing.getId() != null ? String.valueOf(existing.getId()) : requestId);
        return new SubmitResponse(requestId, messageId, existing.getStatus(), existing.getOperator(), existing.getSessionId());
    }

    /**
     * Cleanup expired entries every 60 seconds.
     * Prevents unbounded memory growth.
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupExpiredSignatures() {
        Instant cutoff = Instant.now().minusSeconds(dedupWindowMinutes * 60L);
        int removed = 0;
        Iterator<Map.Entry<String, Instant>> it = recentSignatures.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(cutoff)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Dedup cache cleanup: removed {} expired entries, {} remaining", removed, recentSignatures.size());
        }
    }
}

