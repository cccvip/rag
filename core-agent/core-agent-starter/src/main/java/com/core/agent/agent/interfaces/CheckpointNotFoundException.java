package com.core.agent.agent.interfaces;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class CheckpointNotFoundException extends RuntimeException {

    public CheckpointNotFoundException(String token) {
        super("Checkpoint not found: " + token);
    }
}
