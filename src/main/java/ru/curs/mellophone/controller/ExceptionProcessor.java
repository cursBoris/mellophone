package ru.curs.mellophone.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ru.curs.mellophone.logic.EAuthServerLogic;

@ControllerAdvice
public class ExceptionProcessor extends ResponseEntityExceptionHandler {
    @ExceptionHandler(EAuthServerLogic.class)
    protected ResponseEntity<Object> handleAuthException(EAuthServerLogic ex, WebRequest request) {
        return this.handleExceptionInternal(ex, ex.getMessage(), new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

}