// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationExceptions(MethodArgumentNotValidException ex, Locale locale) {
        List<Map<String, String>> errors = new ArrayList<>();

        for(FieldError error : ex.getBindingResult().getFieldErrors()) {
            String msgCode = error.getDefaultMessage();
            if (msgCode != null) {
                String msg = messageSource.getMessage(msgCode, null, null, locale);
                errors.add(Map.of("field", error.getField(), "message", msg));
            } else {
                String msg = messageSource.getMessage(error, locale);
                errors.add(Map.of("field", error.getField(), "message", msg));
            }
        }

        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

}
