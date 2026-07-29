package com.encore.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 에러 응답은 RFC 7807(Problem Detail)로 통일한다.
 * 프레임워크가 아는 예외(경로 변수 타입 불일치, 요청 본문 검증 실패, 404 No handler 등)는
 * spring.mvc.problemdetails.enabled=true가 이미 Problem으로 변환하므로
 * 여기서는 도메인이 던지는 예외만 다룬다.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiNotFoundException.class)
    public ProblemDetail handleNotFound(ApiNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("리소스를 찾을 수 없습니다");
        return problem;
    }

    /** 도메인 불변식 위반(UNKNOWN 예측 유형 등)은 서버 오류가 아니라 요청 오류다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("잘못된 요청");
        return problem;
    }

    /** 유니크 충돌(같은 아티스트·날짜의 이벤트 재등록 등)은 409로 알려 재시도를 막는다. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "이미 존재하는 데이터와 충돌합니다 (같은 아티스트·날짜의 이벤트가 등록되어 있는지 확인하세요)");
        problem.setTitle("데이터 충돌");
        return problem;
    }
}
