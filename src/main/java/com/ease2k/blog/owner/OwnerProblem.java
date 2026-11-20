package com.ease2k.blog.owner;

import org.springframework.http.HttpStatus;

public final class OwnerProblem extends RuntimeException {

    private final HttpStatus status;

    public OwnerProblem(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static OwnerProblem bad(String message) {
        return new OwnerProblem(HttpStatus.BAD_REQUEST, message);
    }

    public static OwnerProblem conflict(String message) {
        return new OwnerProblem(HttpStatus.CONFLICT, message);
    }

    public static OwnerProblem missing(String message) {
        return new OwnerProblem(HttpStatus.NOT_FOUND, message);
    }
}
