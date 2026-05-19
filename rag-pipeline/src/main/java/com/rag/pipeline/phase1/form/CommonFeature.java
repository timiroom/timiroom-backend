package com.rag.pipeline.phase1.form;

/**
 * Step5 - 공통 기능 체크박스
 * 소셜로그인 | 회원가입/탈퇴 | 알림 | 검색 | 관리자 페이지 | 마이 페이지 | 파일 업로드 | 결제
 */
public record CommonFeature(
    String featureName,  // 기능 이름
    boolean selected,    // 체크 여부
    String description   // 기능 설명 (선택 시 입력, nullable)
) {}
