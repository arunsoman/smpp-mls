package com.cascade.smppmls.service;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.SQLException;

@Service
public class ApplicationShutdownService {

    @Autowired
    private H2DatabaseDumpService h2DatabaseDumpService;

    @PreDestroy
    public void onShutdown() {
        try {
            h2DatabaseDumpService.dumpDatabaseToFile("./dump");
        } catch (SQLException | IOException e) {
            e.printStackTrace();
        }
    }
}
