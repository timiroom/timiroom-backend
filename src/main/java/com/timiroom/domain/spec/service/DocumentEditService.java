package com.timiroom.domain.spec.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.infra.consistency.ConsistencyServiceClient;
import com.timiroom.infra.ragpipeline.DocumentEditClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DocumentEditService {
    private final DocumentEditClient client;
    private final ConsistencyServiceClient consistency;
    private final ObjectMapper mapper;

    public static String docType(ArtifactType type) {
        return switch(type) {
            case PRD -> "prd"; case FEATURE_LIST -> "features";
            case API_SPEC -> "api"; case DB_SCHEMA -> "erd";
            default -> throw new IllegalArgumentException("수정할 수 없는 문서 종류입니다");
        };
    }
    public DocumentEditResult propose(DocumentBundle base, List<ArtifactType> targets,
            String instruction, List<String> constraints) {
        if (instruction == null || instruction.isBlank() || targets == null || targets.isEmpty()
                || targets.size() != new HashSet<>(targets).size())
            throw new IllegalArgumentException("문서와 수정 요청을 확인해주세요");
        var docs = documents(base);
        // Validate the entire request before making a billable call.
        for (var target : targets) {
            docType(target);
            if (!docs.containsKey(target)) throw new IllegalArgumentException("기준 명세에 대상 문서가 없습니다");
        }
        if(instruction.startsWith("문서 전체 재작성:")) {
            String request=instruction.substring("문서 전체 재작성:".length()).trim();
            if(request.isBlank()) throw new IllegalArgumentException("수정 요청이 필요합니다");
            var rewritten=new ArrayList<DocumentEditResult.Change>();
            for(var target:targets) rewritten.addAll(proposeWholeDocument(base,target,request,constraints).changes());
            return new DocumentEditResult(rewritten,"CONSISTENCY_REVISION_AGENT");
        }
        var changes = new ArrayList<DocumentEditResult.Change>();
        var request = instruction + (constraints == null || constraints.isEmpty() ? "" : "\n제약 사항:\n" + String.join("\n", constraints));
        for (var target : targets) {
            var stored = docs.get(target);
            var original = DocumentShape.forEditor(target,stored,mapper);
            var response = client.edit(docType(target), original.deepCopy(), request);
            if ("chat".equals(response.path("intent").asText())) continue;
            if (!"edit".equals(response.path("intent").asText()) || !response.path("edits").isArray())
                throw new IllegalStateException("수정 diff가 올바르지 않습니다");
            ObjectNode proposed = original.deepCopy();
            var seen = new HashSet<String>();
            for (var edit : response.get("edits")) {
                var section = edit.path("section").asText();
                if (section.isBlank() || !seen.add(section) || !original.has(section)
                        || !edit.has("before") || !original.get(section).equals(edit.get("before")) || !edit.hasNonNull("after"))
                    throw new IllegalStateException("기준 문서와 수정 diff가 일치하지 않습니다");
                proposed.set(section, edit.get("after").deepCopy());
            }
            if (!proposed.equals(original)) changes.add(new DocumentEditResult.Change(target, DocumentShape.restore(target,stored,proposed),
                response.get("edits").deepCopy(), response.path("reply").asText("문서 수정")));
        }
        return new DocumentEditResult(changes, "PIPELINE_SECTION_EDITOR");
    }
    /** Explicit whole-document mode uses the existing consistency revision engine. */
    public DocumentEditResult proposeWholeDocument(DocumentBundle base, ArtifactType target,
            String instruction, List<String> constraints) {
        docType(target);
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("수정 요청이 필요합니다");
        var docs = documents(base);
        if (!docs.containsKey(target)) throw new IllegalArgumentException("기준 문서에 대상이 없습니다");
        var siblings = new LinkedHashMap<>(docs);
        var current = siblings.remove(target);
        var response = consistency.reviseArtifact(Map.of("projectId", base.projectId(), "artifactType", target.name(),
            "currentContent", current, "instruction", instruction, "siblingArtifacts", siblings,
            "constraints", constraints == null ? List.of() : constraints));
        var revised = response.path("revisedContent");
        revised=DocumentShape.restore(target,current,revised);
        if (!DocumentShape.valid(target,revised)) throw new IllegalStateException("수정 문서의 형식이 올바르지 않습니다");
        return new DocumentEditResult(revised.equals(current) ? List.of() : List.of(new DocumentEditResult.Change(
            target, revised.deepCopy(), mapper.createArrayNode(), response.path("changeSummary").asText())), "CONSISTENCY_REVISION_AGENT");
    }
    Map<ArtifactType, JsonNode> documents(DocumentBundle bundle) {
        var result = new LinkedHashMap<ArtifactType, JsonNode>();
        for (var doc : bundle.documents()) {
            try {
                var parsed = mapper.readTree(doc.content());
                if (!DocumentShape.valid(doc.type(),parsed) || result.putIfAbsent(doc.type(), parsed) != null)
                    throw new IllegalArgumentException("중복 문서 또는 JSON 객체가 아닌 문서입니다");
            } catch (java.io.IOException e) { throw new IllegalArgumentException("문서 JSON이 올바르지 않습니다", e); }
        }
        return result;
    }
}
