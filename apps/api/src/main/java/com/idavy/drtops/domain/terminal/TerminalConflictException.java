package com.idavy.drtops.domain.terminal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class TerminalConflictException extends IllegalStateException {
    public TerminalConflictException(String message) {
        super(message);
    }
}
