package com.supermarket.agent;

import com.supermarket.dto.ResponseDTO;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-10)
@RestControllerAdvice
public class AgentExceptionHandler {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ResponseDTO<Void>> handle(AgentException error) {
        return ResponseEntity.status(error.status())
                .body(ResponseDTO.error(error.status(), error.getMessage()));
    }
}
