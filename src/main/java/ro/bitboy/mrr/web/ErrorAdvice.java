package ro.bitboy.mrr.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handle(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("bad_request", "Request body is missing or not valid JSON.", null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handle(HttpRequestMethodNotSupportedException e,
                                                HttpServletRequest request) {
        log.warn("{} {} — method not allowed", e.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("method_not_allowed",
                        "This URL does not accept " + e.getMethod() + ".",
                        request.getRequestURI()));
    }

    /**
     * A path that matches no controller and no file. Left to the catch-all below it would be
     * answered with 500 and logged at ERROR with a stack trace — which is both wrong for the
     * caller and corrosive for anyone who needs ERROR to mean something, since a public
     * instance is probed for {@code .env} and {@code .git/HEAD} within minutes of coming up.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handle(NoResourceFoundException e,
                                                HttpServletRequest request) {
        log.debug("404 {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "No such endpoint.", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handle(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "Unexpected server error.", null));
    }
}
