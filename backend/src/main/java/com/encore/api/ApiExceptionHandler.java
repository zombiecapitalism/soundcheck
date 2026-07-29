package com.encore.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 에러 응답은 RFC 7807(Problem Detail)로 통일한다.
 * 프레임워크가 아는 예외(경로 변수 타입 불일치, 404 No handler, 405 등)는
 * spring.mvc.problemdetails.enabled=true가 이미 Problem으로 변환하므로
 * 여기서는 도메인 조회 실패만 다룬다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiNotFoundException.class)
    public ProblemDetail handleNotFound(ApiNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("리소스를 찾을 수 없습니다");
        return problem;
    }
}
