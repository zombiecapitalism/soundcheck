package com.encore.api;

/** 조회 대상이 없을 때. ApiExceptionHandler가 404 Problem Detail로 변환한다. */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
