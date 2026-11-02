package com.example.assistant.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Every error is returned as {"detail": "..."} so the UI can show it directly. Internals are never leaked. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<Map<String, Object>> body(int status, String msg) {
        return ResponseEntity.status(status).body(Map.of("detail", msg));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) { return body(e.status(), e.getMessage()); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooBig(MaxUploadSizeExceededException e) {
        return body(413, "The file is too large. The limit is 10 MB per PDF.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, Object>> noFile(MissingServletRequestPartException e) { return body(400, "No file was provided."); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) { return body(400, "Invalid request."); }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException e) {
        log.error("Unexpected error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Something went wrong on our side. Please try again.");
    }
}
