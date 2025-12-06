package com.cascade.smppmls.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "delayed_message_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelayedMessageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_message_id")
    private Long originalMessageId;

    @Column(name = "msisdn")
    private String msisdn;

    @Column(name = "entry_time")
    private Instant entryTime;

    @Column(name = "exit_time")
    private Instant exitTime;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "status")
    private String status;

    @Column(name = "reason")
    private String reason;
}
