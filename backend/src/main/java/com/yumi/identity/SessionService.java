package com.yumi.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    static final String SESSION_COOKIE = "YUMI_SESSION";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminSession authenticate(String username, String password) {
        var account = jdbcTemplate.query(
                "SELECT id, username, password_hash, status FROM admin_accounts WHERE username = ?",
                resultSet -> resultSet.next()
                        ? new Account(resultSet.getLong("id"), resultSet.getString("username"),
                        resultSet.getString("password_hash"), resultSet.getString("status"))
                        : null,
                username);
        if (account == null || !"ACTIVE".equals(account.status())
                || !passwordEncoder.matches(password, account.passwordHash())) {
            return null;
        }

        var tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        sessions.put(token, new Session(account.id(), account.username()));
        return new AdminSession(token, account.id(), account.username());
    }

    public Session current(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    record AdminSession(String token, long id, String username) {
    }

    record Session(long id, String username) {
    }

    private record Account(long id, String username, String passwordHash, String status) {
    }
}
