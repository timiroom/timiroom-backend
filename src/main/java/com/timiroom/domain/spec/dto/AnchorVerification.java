package com.timiroom.domain.spec.dto;
import java.util.List;

/**
 * 명세 지문 검증 결과. verified는 세 단계가 모두 PASS일 때만 true(체인 기록 전이면 false).
 * DOCUMENTS: 스냅샷 본문을 다시 해시해 매니페스트와 같은가(본문·revision·시각이 그대로인가)
 * MANIFEST: 매니페스트 원문의 SHA-256이 저장된 지문과 같고, prevDigest가 직전 revision 지문과 이어지는가
 * CHAIN: 블록체인에 기록된 지문이 같은가
 */
public record AnchorVerification(boolean verified,List<Check> checks) {
    public enum Step { DOCUMENTS, MANIFEST, CHAIN }
    public enum Status { PASS, FAIL, SKIPPED, ERROR }
    public record Check(Step step,Status status) {}
    public AnchorVerification { checks=List.copyOf(checks); }
    public static AnchorVerification of(Status documents,Status manifest,Status chain) {
        boolean ok=documents==Status.PASS && manifest==Status.PASS && chain==Status.PASS;
        return new AnchorVerification(ok,List.of(new Check(Step.DOCUMENTS,documents),new Check(Step.MANIFEST,manifest),new Check(Step.CHAIN,chain)));
    }
}
