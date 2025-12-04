package com.cascade.smppmls.service;

/**
 * Record to hold message statistics during shutdown
 */
public record MessageStats(long queued, long inFlight, long total) {
}
