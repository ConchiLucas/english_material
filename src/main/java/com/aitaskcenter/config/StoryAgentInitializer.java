package com.aitaskcenter.config;

import com.aitaskcenter.service.StoryAgentService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StoryAgentInitializer implements ApplicationRunner {
    private final StoryAgentService service;

    public StoryAgentInitializer(StoryAgentService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.initializeDefaults();
    }
}
