package com.smartblock.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = Logger.getLogger(ApiErrorHandler.class.getName());

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> databaseError(DataAccessException ex) {
        LOG.log(Level.SEVERE, "SmartBlock database operation failed", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "ok", false,
                "message", "SmartBlock could not read the database. In MySQL, run database/schema.sql first and database/seed.sql second. Check the first SQL or Java error in the terminal."
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalidInput(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "ok", false,
                "message", ex.getMessage() == null ? "Please check the entered values." : ex.getMessage()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpectedError(Exception ex) {
        LOG.log(Level.SEVERE, "Unexpected SmartBlock error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "ok", false,
                "message", "Something went wrong. Refresh and try again. If it repeats, check the app terminal for the error."
        ));
    }
}
