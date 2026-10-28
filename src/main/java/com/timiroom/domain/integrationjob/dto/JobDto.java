package com.timiroom.domain.integrationjob.dto;
import com.fasterxml.jackson.databind.JsonNode;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import java.util.UUID;
public record JobDto(UUID jobId,IntegrationJob.Kind kind,IntegrationJob.State status,String bindingKey,int attempts,
                     JsonNode result,String error) {}
