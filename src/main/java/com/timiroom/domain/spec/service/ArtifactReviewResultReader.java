package com.timiroom.domain.spec.service;
import java.util.UUID;
/** An artifact review PASS binds the precise proposal revision and all proposed contents. */
public interface ArtifactReviewResultReader {
    boolean hasPass(UUID proposalId, int proposalRevision, String resultHash);
}
