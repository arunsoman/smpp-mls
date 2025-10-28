package com.cascade.smppmls.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Simple API key interceptor - requires header X-API-KEY to be present. This is a stub for now.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    @Value("${api.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${api.security.api-key:}")
    private String validApiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Skip authentication if disabled
        if (!securityEnabled) {
            logger.debug("API security disabled - allowing request {} {}", request.getMethod(), request.getRequestURI());
            return true;
        }

        String key = request.getHeader("X-API-KEY");
        if (key == null || key.isBlank()) {
            logger.debug("Missing API key for request {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Missing X-API-KEY header");
            return false;
        }
        
        // Validate key if configured
        if (!validApiKey.isBlank() && !validApiKey.equals(key)) {
            logger.warn("Invalid API key for request {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid X-API-KEY");
            return false;
        }
        
        return true;
    }
}
