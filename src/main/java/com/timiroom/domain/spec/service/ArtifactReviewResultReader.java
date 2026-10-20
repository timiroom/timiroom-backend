package com.timiroom.domain.spec.service;
import java.util.UUID;
/** An artifact review PASS binds the precise proposal revision and all proposed contents. */
public interface ArtifactReviewResultReader {
    boolean hasPass(UUID proposalId, int proposalRevision, String resultHash);
    com.fasterxml.jackson.databind.JsonNode latest(UUID proposalId,int proposalRevision,String resultHash);
    /** 이 변경안 내용 그대로 교차검증이 끝까지(입력 누락 없이) 돌았는지. 경고가 있어도 참이다. */
    default boolean hasCompletedReview(UUID proposalId,int proposalRevision,String resultHash) {
        var result=latest(proposalId,proposalRevision,resultHash);
        return result!=null && result.path("inputComplete").asBoolean(false) && result.path("findings").isArray();
    }
}
