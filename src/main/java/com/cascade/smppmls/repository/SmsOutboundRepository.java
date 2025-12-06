package com.cascade.smppmls.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cascade.smppmls.entity.SmsOutboundEntity;

@Repository
public interface SmsOutboundRepository extends JpaRepository<SmsOutboundEntity, Long> {
    // find by client_msg_id for idempotency
    SmsOutboundEntity findByClientMsgId(String clientMsgId);
    
    // find by request_id
    SmsOutboundEntity findByRequestId(String requestId);
    
    // find by MSISDN (phone number)
    java.util.List<SmsOutboundEntity> findByMsisdn(String msisdn);
    
    // find by SMSC message ID
    SmsOutboundEntity findBySmscMsgId(String smscMsgId);
    
    // find by status and session
    org.springframework.data.domain.Page<SmsOutboundEntity> findByStatusAndSessionId(String status, String sessionId, org.springframework.data.domain.Pageable pageable);
    
    // find by status, session and priority
    org.springframework.data.domain.Page<SmsOutboundEntity> findByStatusAndSessionIdAndPriority(String status, String sessionId, String priority, org.springframework.data.domain.Pageable pageable);
    
    // count by status
    long countByStatus(String status);

    // find retry candidates
    org.springframework.data.domain.Page<SmsOutboundEntity> findByStatusAndNextRetryAtBefore(String status, java.time.Instant before, org.springframework.data.domain.Pageable pageable);

    // find delayed messages
    java.util.List<SmsOutboundEntity> findByStatusAndCreatedAtBeforeAndPriorityNot(String status, java.time.Instant before, String priority);

    // find retry candidates by operator (for per-operator retry queues)
    org.springframework.data.domain.Page<SmsOutboundEntity> findByStatusAndOperatorAndNextRetryAtBefore(
        String status, String operator, java.time.Instant before, org.springframework.data.domain.Pageable pageable);

    // get distinct operators with retry messages
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT o.operator FROM SmsOutboundEntity o WHERE o.status = 'RETRY' AND o.operator IS NOT NULL")
    java.util.List<String> findDistinctOperatorsWithRetry();

    // count by status and operator (for per-operator retry stats)
    long countByStatusAndOperator(String status, String operator);
}
