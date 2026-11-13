package io.github.rainyinsaigon.fraudlinker.service.api;

import io.github.rainyinsaigon.fraudlinker.LinkerException;
import io.github.rainyinsaigon.fraudlinker.service.app.ConflictException;
import io.github.rainyinsaigon.fraudlinker.service.app.NotFoundException;
import io.github.rainyinsaigon.fraudlinker.service.engine.EngineUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Turns failures into RFC 9457 problem responses. Messages are written for the
 * caller; internal details (stack traces, SQL) are logged, never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler({LinkerException.class, IllegalArgumentException.class})
    ProblemDetail badInput(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParameter(ConstraintViolationException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more parameters are invalid.");
        problem.setProperty("errors", e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.warn("Database rejected a write", e);
        return problem(HttpStatus.CONFLICT, "Conflict", "The request conflicts with stored data.");
    }

    @ExceptionHandler(EngineUnavailableException.class)
    ProblemDetail unavailable(EngineUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "Linking is temporarily unavailable. Retry later.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more fields are invalid.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .sorted(java.util.Comparator.comparing(org.springframework.validation.FieldError::getField))
                .map(error -> Map.of("field", snakeCase(error.getField()),
                        "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more parameters are invalid.");
        problem.setProperty("errors", e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage())))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    /** Reports field names the way clients send them: {@code createdAt} becomes {@code created_at}. */
    static String snakeCase(String field) {
        return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
