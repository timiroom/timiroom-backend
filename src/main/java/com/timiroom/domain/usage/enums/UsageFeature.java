package com.timiroom.domain.usage.enums;

/** 사용량을 나눠 보여 주는 기능 단위. 화면 이름은 프론트가 붙인다. */
public enum UsageFeature {
    /** 새 프로젝트 기획 문서 생성 */
    PROJECT_GENERATION,
    /** 새 프로젝트 만들기 대화 */
    PROJECT_CHAT,
    /** 문서 화면의 AI 채팅 */
    AI_CHAT,
    /** AI로 문서 고치기 */
    DOCUMENT_EDIT,
    /** 기술 스택·페르소나·기능 추천 */
    RECOMMENDATION,
    /** PR 정합성 검사 */
    PR_CONSISTENCY,
    /** 변경안 생성·문서 교차검증 */
    SPEC_REVIEW
}
