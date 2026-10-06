package com.schwab.urlshortener.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Issues JWTs in exchange for valid credentials.
 *
 * <p>Both human users and the agent service account obtain a token the same way: {@code POST}
 * their credentials to {@code /auth/token} and use the returned bearer token on subsequent calls.
 */
@RestController
public class AuthController {

    private final JwtService jwtService;
    private final UserStore userStore;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param jwtService      the token issuer
     * @param userStore       the user directory
     * @param passwordEncoder the password matcher
     */
    public AuthController(JwtService jwtService, UserStore userStore, PasswordEncoder passwordEncoder) {
        this.jwtService = jwtService;
        this.userStore = userStore;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticates credentials and issues a token.
     *
     * @param request the username and password
     * @return 200 OK with a {@link TokenResponse}, or 401 if the credentials are invalid
     */
    @PostMapping("/auth/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request) {
        UserStore.AppUser user = userStore.find(request.username()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String token = jwtService.generateToken(user.username(), user.roles());
        return ResponseEntity.ok(
                new TokenResponse(token, "Bearer", jwtService.expirationSeconds(), user.roles()));
    }

    /**
     * @param username the login name
     * @param password the password
     */
    public record TokenRequest(@NotBlank String username, @NotBlank String password) {
    }

    /**
     * @param token            the signed JWT
     * @param tokenType        always {@code "Bearer"}
     * @param expiresInSeconds the token lifetime
     * @param roles            the granted authorities
     */
    public record TokenResponse(String token, String tokenType, long expiresInSeconds,
                                List<String> roles) {
    }
}
