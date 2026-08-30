package com.idavy.drtops.domain.onboard;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class OnboardConfigurationConflictException extends IllegalStateException {

    public OnboardConfigurationConflictException(String code) {
        super(code);
    }
}
