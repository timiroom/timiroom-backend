package com.rag.pipeline.phase1.form;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Step5 - 공통 기능 체크박스
 * 소셜로그인 | 회원가입/탈퇴 | 알림 | 검색 | 관리자 페이지 | 마이 페이지 | 파일 업로드 | 결제
 */
public record CommonFeature(
    @JsonAlias("name") String featureName,
    boolean selected,
    String description
) {}
