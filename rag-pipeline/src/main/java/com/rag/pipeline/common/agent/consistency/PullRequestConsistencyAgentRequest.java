package com.rag.pipeline.common.agent.consistency;

import java.util.List;

/** PR 구현과 최신 설계 산출물을 Consistency Agent에 전달하는 구조화된 요청. */
public record PullRequestConsistencyAgentRequest(
        String model,
        String repository,
        int pullNumber,
        String title,
        String body,
        String headRef,
        String baseRef,
        String apiSpec,
        String dbSchema,
        List<ChangedFile> changedFiles
) {
    public PullRequestConsistencyAgentRequest {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    }

    public record ChangedFile(
            String filename,
            String status,
            int additions,
            int deletions,
            String patch
    ) {}
}
