package com.timiroom.domain.spec.dto;
import com.timiroom.domain.spec.entity.SpecAnchor;
import com.timiroom.domain.spec.service.SpecManifest;
import java.time.Instant;

/**
 * 스냅샷의 블록체인 기록 상태. state는 SpecAnchor.State 이름 또는 지문이 아직 없으면 "NONE".
 * chainEnabled=false이면 체인 전송이 꺼져 있다(지문은 DB에만 있음).
 */
public record SpecAnchorDto(String state,boolean chainEnabled,Long chainId,String contractAddress,String projectKey,int revision,
        String digest,String prevDigest,String txHash,Long blockNumber,boolean backfilled,String errorCode,Instant submittedAt,Instant confirmedAt) {
    public static SpecAnchorDto of(SpecAnchor a,boolean chainEnabled) {
        return new SpecAnchorDto(a.getState().name(),chainEnabled,a.getChainId(),a.getContractAddress(),SpecManifest.projectKey(a.getProjectId()),
            a.getRevision(),a.getDigest(),a.getPrevDigest(),a.getTxHash(),a.getBlockNumber(),a.isBackfilled(),a.getErrorCode(),a.getSubmittedAt(),a.getConfirmedAt());
    }
    public static SpecAnchorDto none(Long projectId,int revision,boolean chainEnabled) {
        return new SpecAnchorDto("NONE",chainEnabled,null,null,SpecManifest.projectKey(projectId),revision,null,null,null,null,false,null,null,null);
    }
}
