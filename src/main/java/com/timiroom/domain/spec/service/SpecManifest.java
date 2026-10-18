package com.timiroom.domain.spec.service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timiroom.domain.spec.dto.ProposalRef;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 명세 발행 매니페스트(v1)와 지문. 블록체인에는 지문만 올리고 매니페스트 원문은 spec_anchor에 저장한다.
 * 규칙: 키를 사전순으로 재귀 정렬, 공백 없는 직렬화, UTF-8, 시각은 UTC 밀리초 고정 포맷, 문서는 type → artifactId 순.
 * 지문 = SHA-256(매니페스트 UTF-8 바이트). 검증은 저장된 원문으로 하므로 다시 직렬화한 결과에 기대지 않는다.
 * 발행자(회원 ID)는 넣지 않는다 — 매니페스트는 외부 검증용으로 내려받을 수 있다.
 */
public final class SpecManifest {
    public static final int VERSION=1;
    // 전역 ObjectMapper 설정이 바뀌어도 지문이 달라지지 않게 전용 매퍼를 쓴다.
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Comparator<SpecDocumentDto> ORDER=Comparator.comparing((SpecDocumentDto d)->d.type().name()).thenComparing(SpecDocumentDto::artifactId);
    private SpecManifest() {}

    public record Built(String json,String digest) {}

    public static Built build(Long projectId,int revision,UUID snapshotId,Instant publishedAt,List<SpecDocumentDto> documents,ProposalRef proposal,String prevDigest) {
        ObjectNode root=JSON.createObjectNode();
        root.put("v",VERSION).put("projectId",projectId).put("revision",revision)
            .put("snapshotId",snapshotId.toString()).put("publishedAt",time(publishedAt));
        var docs=root.putArray("documents");
        documents.stream().sorted(ORDER).forEach(d->docs.addObject()
            .put("type",d.type().name()).put("artifactId",d.artifactId()).put("version",d.version()).put("hash",d.hash()));
        if(proposal==null) root.putNull("proposal");
        else root.putObject("proposal").put("id",proposal.id().toString()).put("revision",proposal.revision()).put("resultHash",proposal.resultHash());
        if(prevDigest==null) root.putNull("prevDigest"); else root.put("prevDigest",prevDigest);
        try {
            String json=JSON.writeValueAsString(canonical(root));
            return new Built(json,digest(json));
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize manifest",e); }
    }
    public static String digest(String manifestJson) { return DocumentHash.of(manifestJson); }
    /** 체인에서 프로젝트를 구분하는 키. 프로젝트 ID는 작은 정수라 역산되므로 ID를 숨기는 장치는 아니다. */
    public static String projectKey(Long projectId) { return DocumentHash.of("align-it:project:"+projectId); }
    public static String time(Instant instant) { return TIME.format(instant.truncatedTo(ChronoUnit.MILLIS)); }
    public static JsonNode parse(String manifestJson) {
        try { return JSON.readTree(manifestJson); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Invalid stored manifest",e); }
    }
    /** 매니페스트의 proposal 칸을 다시 읽는다. 비었으면 null. */
    public static ProposalRef proposal(JsonNode manifest) {
        var p=manifest.path("proposal");
        if(p.isMissingNode() || p.isNull()) return null;
        return new ProposalRef(UUID.fromString(p.path("id").asText()),p.path("revision").asInt(),p.path("resultHash").isNull()?null:p.path("resultHash").asText());
    }
    // IntegrationJobService.canonical()과 같은 규칙. 지문 규칙이 그쪽 변경에 끌려가지 않게 따로 둔다.
    private static JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            var sorted=JSON.createObjectNode(); var keys=new ArrayList<String>(); value.fieldNames().forEachRemaining(keys::add);
            Collections.sort(keys); keys.forEach(k->sorted.set(k,canonical(value.get(k)))); return sorted;
        }
        if(value.isArray()) { var array=JSON.createArrayNode(); value.forEach(v->array.add(canonical(v))); return array; }
        return value;
    }
}
