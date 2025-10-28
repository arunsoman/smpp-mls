package com.cascade.smppmls.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sms_dlr")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmsDlrEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sms_outbound_id")
    private Long smsOutboundId;

    @Column(name = "smsc_msg_id", length = 64)
    private String smscMsgId;

    @Column(name = "status", length = 50)
    private String status;

    @Column(name = "received_at")
    private Instant receivedAt;
}
