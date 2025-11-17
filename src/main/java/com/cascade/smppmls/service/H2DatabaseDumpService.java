package com.cascade.smppmls.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;

@Service
public class H2DatabaseDumpService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void dumpDatabaseToFile(String outputPath) throws SQLException, IOException {
        Path dumpPath = Paths.get(outputPath);

        // Ensure parent directory exists
        Files.createDirectories(dumpPath.getParent());

        // Delete existing file if present
        Files.deleteIfExists(dumpPath);

        try {
            // Use parameterized approach - escape single quotes
            String sanitizedPath = dumpPath.toAbsolutePath().toString().replace("'", "''");
            String script = "SCRIPT TO '" + sanitizedPath + "'";

            jdbcTemplate.execute(script);
            System.out.println("Database dump completed: " + dumpPath.toAbsolutePath());

        } catch (Exception e) {
            throw new IOException("Failed to dump database to " + outputPath, e);
        }
    }
}
