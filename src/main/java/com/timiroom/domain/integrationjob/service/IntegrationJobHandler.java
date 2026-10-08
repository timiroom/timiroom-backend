package com.timiroom.domain.integrationjob.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
public interface IntegrationJobHandler {
    boolean supports(IntegrationJob.Kind kind);
    JsonNode execute(IntegrationJob job);
    default boolean requiresProvider(IntegrationJob job) { return true; }
    default void onFailure(IntegrationJob job) {}
}
