package com.timiroom.domain.integration.controller;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(basePackages={"com.timiroom.domain.integration.controller","com.timiroom.domain.spec.controller"})
public class IntegrationExceptionHandler {
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<?> denied(SecurityException error) {return response(HttpStatus.FORBIDDEN,"ACCESS_DENIED");}
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<?> unauthenticated() {return response(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED");}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception error) {return response(HttpStatus.BAD_REQUEST,"INVALID_INPUT");}
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException error) {
        String message=error.getMessage()==null?"":error.getMessage();
        for(String code:java.util.List.of("SPEC_CONFLICT","SPEC_NOT_PUBLISHED","ARTIFACT_REVIEW_REQUIRED","IDEMPOTENCY_CONFLICT","RATE_LIMITED","CONCURRENCY_LIMIT"))
            if(message.startsWith(code)) return response(SetLimits.contains(code)?HttpStatus.TOO_MANY_REQUESTS:HttpStatus.CONFLICT,code);
        return response(HttpStatus.BAD_GATEWAY,"SERVICE_UNAVAILABLE");
    }
    private static final java.util.Set<String> SetLimits=java.util.Set.of("RATE_LIMITED","CONCURRENCY_LIMIT");
    private ResponseEntity<?> response(HttpStatus status,String code) {return ResponseEntity.status(status).body(Map.of("code",code));}
}
