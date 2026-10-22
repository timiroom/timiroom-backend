package com.timiroom.domain.integrationjob.service;

/** 테스트에서 패키지 전용 메서드를 부르기 위한 통로 */
public final class IntegrationJobWorkerAccess {
    private IntegrationJobWorkerAccess() {}
    public static com.timiroom.infra.ragpipeline.AiServiceException aiFailure(Throwable failure) { return IntegrationJobWorker.aiFailure(failure); }
}
