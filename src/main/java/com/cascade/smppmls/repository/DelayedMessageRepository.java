package com.cascade.smppmls.repository;

import com.cascade.smppmls.entity.DelayedMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DelayedMessageRepository extends JpaRepository<DelayedMessageLog, Long> {
}
