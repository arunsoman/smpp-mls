package com.cascade.smppmls.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cascade.smppmls.entity.SmsDlrEntity;

@Repository
public interface SmsDlrRepository extends JpaRepository<SmsDlrEntity, Long> {
    // Find all DLRs for a specific outbound message
    java.util.List<SmsDlrEntity> findBySmsOutboundId(Long smsOutboundId);
    
    // Find DLR by SMSC message ID
    SmsDlrEntity findBySmscMsgId(String smscMsgId);
}
