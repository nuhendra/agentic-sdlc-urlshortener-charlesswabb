package com.schwab.urlshortener.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory user directory built from {@link SecurityProperties} at startup, with passwords
 * BCrypt-encoded. This is a stand-in for a real user store or identity provider; the shape
 * (lookup by username → principal with encoded password and roles) is what the rest of the
 * security layer depends on, so swapping in a database or LDAP later is a localized change.
 */
@Component
public class UserStore {

    /**
     * A resolved principal.
     *
     * @param username     the login name
     * @param passwordHash the BCrypt hash of the password
     * @param roles        the granted authorities (each prefixed {@code ROLE_})
     */
    public record AppUser(String username, String passwordHash, List<String> roles) {
    }

    private final Map<String, AppUser> users = new HashMap<>();

    /**
     * @param props   the configured users (plaintext passwords, encoded here)
     * @param encoder the password encoder (BCrypt)
     */
    public UserStore(SecurityProperties props, PasswordEncoder encoder) {
        if (props.users() != null) {
            for (SecurityProperties.UserProps u : props.users()) {
                users.put(u.username(),
                        new AppUser(u.username(), encoder.encode(u.password()), List.copyOf(u.roles())));
            }
        }
    }

    /**
     * Looks up a principal by username.
     *
     * @param username the login name
     * @return the principal if present, otherwise {@link Optional#empty()}
     */
    public Optional<AppUser> find(String username) {
        return Optional.ofNullable(users.get(username));
    }
}
