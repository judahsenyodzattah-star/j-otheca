package com.jotheca.discovery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Global discovery remains disabled until the projection and security gates are approved. */
@Component
@ConfigurationProperties(prefix = "app.discovery")
public class GlobalDiscoveryProperties {

    private boolean globalEnabled;

    public boolean isGlobalEnabled() { return globalEnabled; }
    public void setGlobalEnabled(boolean globalEnabled) { this.globalEnabled = globalEnabled; }
}
