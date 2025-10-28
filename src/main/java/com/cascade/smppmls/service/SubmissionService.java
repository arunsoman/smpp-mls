package com.cascade.smppmls.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cascade.smppmls.api.SubmitRequest;
import com.cascade.smppmls.api.SubmitResponse;
import com.cascade.smppmls.entity.SmsOutboundEntity;
import com.cascade.smppmls.repository.SmsOutboundRepository;
import com.cascade.smppmls.router.OperatorRouter;
import com.cascade.smppmls.util.MsisdnUtils;

@Service
public class SubmissionService {

    private static final Logger logger = LoggerFactory.getLogger(SubmissionService.class);

    private final SmsOutboundRepository outboundRepository;
    private final OperatorRouter router;

    public SubmissionService(SmsOutboundRepository outboundRepository, OperatorRouter router) {
        this.outboundRepository = outboundRepository;
        this.router = router;
    }

    public SubmitResponse submit(SubmitRequest req) {
        String normalized = MsisdnUtils.normalizeToE164(req.getMsisdn(), "93");
        if (normalized == null) throw new IllegalArgumentException("Invalid msisdn");

        String[] route = router.resolve(normalized);

        String operator = null;
        String sessionId = null;
        if (route != null) {
            operator = route[0];
            // store full session key as operator:systemId so session manager and repository queries align
            sessionId = route[0] + ":" + route[1];
        }
        // Idempotency: if clientMsgId provided and exists, return existing record
        if (req.getClientMsgId() != null && !req.getClientMsgId().isBlank()) {
            SmsOutboundEntity existing = outboundRepository.findByClientMsgId(req.getClientMsgId());
            if (existing != null) {
                // ensure requestId exists
                if (existing.getRequestId() == null) {
                    existing.setRequestId(UUID.randomUUID().toString());
                    outboundRepository.save(existing);
                }
                String existingRequestId = existing.getRequestId();
                String existingMessageId = existing.getSmscMsgId() != null ? existing.getSmscMsgId() : (existing.getId() != null ? String.valueOf(existing.getId()) : existingRequestId);
                return new SubmitResponse(existingRequestId, existingMessageId, existing.getStatus(), existing.getOperator(), existing.getSessionId());
            }
        }

        String requestId = UUID.randomUUID().toString();

        SmsOutboundEntity entity = SmsOutboundEntity.builder()
                .requestId(requestId)
                .clientMsgId(req.getClientMsgId())
                .msisdn(normalized)
                .message(req.getMessage())
                .priority(req.getPriority())
                .operator(operator)
                .sessionId(sessionId)
                .status("QUEUED")
                .build();

        // persist
        SmsOutboundEntity saved = outboundRepository.save(entity);

        logger.info("Persisted outbound message id={} requestId={} -> {} (operator={}, session={})", saved.getId(), saved.getRequestId(), normalized, operator, sessionId);

        // For now messageId equals DB id as string until SMSC responds
        String messageId = saved.getId() != null ? String.valueOf(saved.getId()) : requestId;

        return new SubmitResponse(requestId, messageId, "QUEUED", operator, sessionId);
    }
}
