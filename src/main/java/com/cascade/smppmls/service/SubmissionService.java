package com.cascade.smppmls.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.util.MsisdnUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final SmsOutboundRepository outboundRepository;
    private final OperatorRouter router;

    public SubmitResponse submit(SubmitRequest req) {
        String normalized = MsisdnUtils.normalizeToE164(req.getMsisdn(), "93");
        if (normalized == null) throw new IllegalArgumentException("Invalid msisdn");
        if( normalized.startsWith("+9374")){
            throw new IllegalArgumentException("et msisdn");
        }

        String[] route = router.resolve(normalized);

        String operator = null;
        String sessionId = null;
        if (route != null) {
            operator = route[0];
            // Use the sessionId directly from router (it's already the correct key: uuId or operator:systemId)
            sessionId = route[1];
        }
        // Idempotency: if clientMsgId provided and exists, return existing record
        if (req.getClientMsgId() != null && !req.getClientMsgId().isBlank()) {
            try {
                java.util.List<SmsOutboundEntity> existingList = outboundRepository.findByClientMsgId(req.getClientMsgId());
                if (existingList != null && !existingList.isEmpty()) {
                    SmsOutboundEntity existing = existingList.get(0);
                    // ensure requestId exists
                    if (existing.getRequestId() == null) {
                        existing.setRequestId(UUID.randomUUID().toString());
                        outboundRepository.save(existing);
                    }
                    String existingRequestId = existing.getRequestId();
                    String existingMessageId = existing.getSmscMsgId() != null ? existing.getSmscMsgId() : (existing.getId() != null ? String.valueOf(existing.getId()) : existingRequestId);
                    return new SubmitResponse(existingRequestId, existingMessageId, existing.getStatus(), existing.getOperator(), existing.getSessionId());
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        String requestId = UUID.randomUUID().toString();
        
        // Content-based Idempotency
        // Calculate SHA-256 signature of normalized MSISDN + message content
        String signature = null;
        try {
            String raw = normalized + ":" + req.getMessage();
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            signature = java.util.HexFormat.of().formatHex(hash);
            
            // Check if signature exists
            java.util.Optional<SmsOutboundEntity> duplicate = outboundRepository.findBySignature(signature);
            if (duplicate.isPresent()) {
                SmsOutboundEntity existing = duplicate.get();
                log.info("Duplicate submission detected (signature={}). Returning existing id={}", signature, existing.getId());
                // ensure requestId exists
                if (existing.getRequestId() == null) {
                    existing.setRequestId(UUID.randomUUID().toString());
                    outboundRepository.save(existing);
                }
                String existingRequestId = existing.getRequestId();
                String existingMessageId = existing.getSmscMsgId() != null ? existing.getSmscMsgId() : (existing.getId() != null ? String.valueOf(existing.getId()) : existingRequestId);
                return new SubmitResponse(existingRequestId, existingMessageId, existing.getStatus(), existing.getOperator(), existing.getSessionId());
            }
        } catch (Exception e) {
            log.warn("Error calculating signature for idempotency: {}", e.getMessage());
            // proceed without signature if error
        }

        SmsOutboundEntity entity = SmsOutboundEntity.builder()
                .requestId(requestId)
                .clientMsgId(req.getClientMsgId())
                .msisdn(normalized)
                .message(req.getMessage())
                .signature(signature)
                .priority(req.getPriority())
                .operator(operator)
                .sessionId(sessionId)
                .status("QUEUED")
                .build();

        // persist
        SmsOutboundEntity saved = outboundRepository.save(entity);

        log.info("Persisted outbound message id={} requestId={} -> {} (operator={}, session={})", saved.getId(), saved.getRequestId(), normalized, operator, sessionId);

        // For now messageId equals DB id as string until SMSC responds
        String messageId = saved.getId() != null ? String.valueOf(saved.getId()) : requestId;

        return new SubmitResponse(requestId, messageId, "QUEUED", operator, sessionId);
    }
}
