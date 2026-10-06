package com.schwab.agentic.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Externalized security configuration, bound from the {@code security.*} keys in
 * {@code application.yml}.
 *
 * <p>Demo users and the HMAC secret live in configuration (not hardcoded) so they can be
 * overridden per environment; production would use a secrets manager and a real identity
 * provider.
 *
 * @param jwt   JWT signing and lifetime settings
 * @param users the configured principals (demo defaults; replace in production)
 */
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(Jwt jwt, List<UserProps> users) {

    /**
     * @param secret            HMAC secret for HS256; must be at least 32 bytes
     * @param expirationSeconds token lifetime in seconds
     */
    public record Jwt(String secret, long expirationSeconds) {
    }

    /**
     * @param username the login name
     * @param password the plaintext password (demo only; encoded at startup)
     * @param roles    the granted authorities, each prefixed {@code ROLE_}
     */
    public record UserProps(String username, String password, List<String> roles) {
    }
}
