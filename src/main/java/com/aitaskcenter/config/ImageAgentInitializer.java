package com.aitaskcenter.config;

import com.aitaskcenter.service.ImageAgentService;
import java.sql.SQLException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImageAgentInitializer implements ApplicationRunner {
    private static final int MAX_ATTEMPTS = 3;
    private static final Logger log = LoggerFactory.getLogger(ImageAgentInitializer.class);
    private final ImageAgentService service;

    public ImageAgentInitializer(ImageAgentService service) { this.service = service; }

    @Override
    public void run(ApplicationArguments args) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                service.initializeDefaults();
                return;
            } catch (DataIntegrityViolationException exception) {
                if (!isUniqueConflict(exception)) {
                    throw exception;
                }
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Image Agent default initialization encountered concurrent unique-key conflicts after {} attempts; continuing startup", MAX_ATTEMPTS);
                    return;
                }
            }
        }
    }

    private boolean isUniqueConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
