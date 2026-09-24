package com.yumi.identity;

import com.yumi.shared.error.ApiEnvelope;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.web.RequestIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
                                   HttpServletResponse response) {
        var session = sessionService.authenticate(request.username(), request.password());
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiEnvelope.error(ErrorCode.AUTH_INVALID, RequestIdFilter.currentId(httpRequest)));
        }

        addSessionCookie(response, session.token(), 60 * 60 * 8);
        return ResponseEntity.ok(new SessionResponse(session.id(), session.username()));
    }

    @GetMapping
    public ResponseEntity<?> current(
            @CookieValue(value = SessionService.SESSION_COOKIE, required = false) String token,
            HttpServletRequest request) {
        var session = sessionService.current(token);
        if (session == null) {
            return unauthorized(request);
        }
        return ResponseEntity.ok(new SessionResponse(session.id(), session.username()));
    }

    @DeleteMapping
    public ResponseEntity<Void> logout(
            @CookieValue(value = SessionService.SESSION_COOKIE, required = false) String token,
            HttpServletResponse response) {
        sessionService.invalidate(token);
        addSessionCookie(response, "", 0);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiEnvelope> unauthorized(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiEnvelope.error(ErrorCode.AUTH_REQUIRED, RequestIdFilter.currentId(request)));
    }

    private void addSessionCookie(HttpServletResponse response, String value, int maxAge) {
        var cookie = new Cookie(SessionService.SESSION_COOKIE, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        response.addCookie(cookie);
    }

    record LoginRequest(String username, String password) {
    }

    record SessionResponse(long id, String username) {
    }
}
