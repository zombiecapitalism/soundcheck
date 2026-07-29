package com.encore.prediction;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 예측 가중치 파라미터 — application.yml의 encore.prediction.*로 조정한다.
 * 펜타포트 실제 셋리스트로 검증하며 튜닝할 값들이라 코드에 굳히지 않는다(PRD 2-1).
 */
@Validated
@ConfigurationProperties(prefix = "encore.prediction")
public record PredictionProperties(

        /** 집계 표본: 최근 N회 공연. */
        @DefaultValue("20") @Positive int sampleSize,

        /** 최신 가중치 감쇠(회차당 곱). i번째로 최근인 공연의 가중치 = decay^i. 1.0이면 감쇠 없음. */
        @DefaultValue("0.95") @DecimalMin(value = "0.0", inclusive = false) @DecimalMax("1.0")
        double recencyDecay,

        /** expected_show_type과 같은 유형인 공연의 가중치 배수. 1.0이면 구분 없음. */
        @DefaultValue("1.5") @DecimalMin("1.0") double matchingShowTypeBoost
) {
}
