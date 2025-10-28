package com.cascade.smppmls.router;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cascade.smppmls.config.SmppProperties;

@Component
public class OperatorRouter {

    private final SmppProperties smppProperties;

    public OperatorRouter(SmppProperties smppProperties) {
        this.smppProperties = smppProperties;
    }

    /**
     * Resolve operator id and a session systemId for a normalized E.164 msisdn.
     * Returns String[]{operatorId, systemId} or null if none found.
     */
    public String[] resolve(String e164Msisdn) {
        if (e164Msisdn == null) return null;
        // strip leading + for matching
        String digits = e164Msisdn.replaceAll("\\D", "");

        Map<String, SmppProperties.Operator> ops = smppProperties.getOperators();
        if (ops == null) return null;

        for (Map.Entry<String, SmppProperties.Operator> e : ops.entrySet()) {
            String operatorId = e.getKey();
            SmppProperties.Operator op = e.getValue();
            List<String> prefixes = op.getPrefixes();
            if (prefixes != null) {
                for (String p : prefixes) {
                    String normalizedPrefix = p.replaceAll("\\D", "");
                    if (!normalizedPrefix.isEmpty() && digits.startsWith(normalizedPrefix)) {
                        // choose first configured session (simple round-robin can be added later)
                        if (op.getSessions() != null && !op.getSessions().isEmpty()) {
                            String systemId = op.getSessions().get(0).getSystemId();
                            return new String[] { operatorId, systemId };
                        }
                    }
                }
            }
        }
        return null;
    }
}
