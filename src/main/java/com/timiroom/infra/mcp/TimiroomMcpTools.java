package com.timiroom.infra.mcp;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.timiroom.domain.integration.dto.*;
import com.timiroom.domain.integration.service.IntegrationAccessService;
import com.timiroom.domain.spec.dto.*;
import com.timiroom.domain.spec.service.*;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import com.timiroom.domain.graph.dto.GraphResponse;
import com.timiroom.domain.graph.service.GraphCalculator;
import com.timiroom.domain.integrationjob.service.*;
import com.timiroom.domain.integrationjob.repository.IntegrationJobRepository;
import com.timiroom.domain.integrationjob.entity.IntegrationJob;
import com.timiroom.domain.github.*;
import com.timiroom.domain.project.repository.ProjectMemberRepository;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="integration.enabled",havingValue="true")
public class TimiroomMcpTools {
    private final IntegrationAccessService access;
    private final DocumentAccessService documents;
    private final SpecSnapshotService snapshots;
    private final SpecChangeService changes;
    private final GraphCalculator graph;
    private final IntegrationTaskService tasks;
    private final IntegrationJobService jobs;
    private final IntegrationJobRepository jobRepository;
    private final ProjectRepoLinkRepository repoLinks;
    private final GithubRepoRepository repos;
    private final ProjectMemberRepository members;
    private final PullRequestConsistencyService pullRequests;
    private final McpPaginationService pages;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Value("${frontend.url:http://localhost:3000}") private String frontend;
    public record Definition(String name,String description,McpSchema.JsonSchema schema,boolean readOnly) {}
    private static Map<String,Object> string() {return Map.of("type","string","minLength",1);}
    private static Map<String,Object> uuid() {return Map.of("type","string","format","uuid");}
    private static Map<String,Object> integer(long min,long max) {return Map.of("type","integer","minimum",min,"maximum",max);}
    private static Map<String,Object> docType() {return Map.of("type","string","enum",List.of("PRD","FEATURE_LIST","API_SPEC","DB_SCHEMA"));}
    private static Map<String,Object> array(Map<String,Object> items,int min,int max) {return Map.of("type","array","items",items,"minItems",min,"maxItems",max,"uniqueItems",true);}
    private static Map<String,Object> properties(Object... pairs) {
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private static Definition definition(String suffix,String description,boolean readOnly,Map<String,Object> properties,String... required) {
        return new Definition("timiroom_"+suffix,description,new McpSchema.JsonSchema("object",properties,List.of(required),false,null,null),readOnly);
    }
    private static final List<Definition> DEFINITIONS=List.of(
        definition("list_projects","동의한 프로젝트와 확정 명세 revision을 조회합니다.",true,properties("cursor",string(),"limit",integer(1,50))),
        definition("get_project_context","프로젝트 설명, 현재 역할, 연결 저장소와 확정 기준을 조회합니다.",true,properties("projectId",integer(1,Long.MAX_VALUE)),"projectId"),
        definition("get_spec_manifest","확정 명세의 문서 ID, version, hash를 조회합니다.",true,properties("projectId",integer(1,Long.MAX_VALUE),"snapshotId",uuid()),"projectId"),
        definition("read_spec","불변 명세 원문을 부분 조회합니다. 모든 페이지를 합쳐 hash를 확인하세요.",true,properties("projectId",integer(1,Long.MAX_VALUE),"snapshotId",uuid(),"documentType",docType(),"cursor",string(),"maxChars",integer(2,24000)),"projectId","snapshotId","documentType"),
        definition("get_change_impact","같은 명세 기준의 문서 그래프와 최대 깊이 2의 변경 영향을 조회합니다.",true,properties("projectId",integer(1,Long.MAX_VALUE),"snapshotId",uuid(),"proposalId",uuid(),"focusNodeIds",array(string(),0,100),"cursor",string()),"projectId","snapshotId"),
        definition("propose_spec_change","기존 티미룸 수정 엔진으로 변경안을 만듭니다. 원본 반영은 티미룸 화면의 검증과 PM 승인이 필요합니다.",false,properties("projectId",integer(1,Long.MAX_VALUE),"snapshotId",uuid(),"targetDocumentTypes",array(docType(),1,4),"instruction",Map.of("type","string","minLength",1,"maxLength",8000),"constraints",array(Map.of("type","string","maxLength",1000),0,20),"idempotencyKey",Map.of("type","string","minLength",1,"maxLength",128)),"projectId","snapshotId","targetDocumentTypes","instruction","idempotencyKey"),
        definition("get_spec_change","변경안 상태, 바뀐 문서와 diff, 영향, 교차검증 상태, 승인 링크를 조회합니다. 문서 원문은 documentType을 지정하면 함께 줍니다.",true,properties("projectId",integer(1,Long.MAX_VALUE),"proposalId",uuid(),"documentType",docType(),"cursor",string()),"projectId","proposalId"),
        definition("start_artifact_review","변경안 전체 문서의 티미룸 교차검증 작업을 시작합니다. ARTIFACT_REVIEW는 문서 검증입니다.",false,properties("projectId",integer(1,Long.MAX_VALUE),"proposalId",uuid(),"proposalRevision",integer(1,Integer.MAX_VALUE),"idempotencyKey",Map.of("type","string","minLength",1,"maxLength",128)),"projectId","proposalId","proposalRevision","idempotencyKey"),
        definition("start_consistency_check","고정 명세와 PR 커밋을 티미룸 Fact Gate로 검사합니다. GitHub 댓글이나 Check Run을 게시하지 않습니다.",false,properties("projectId",integer(1,Long.MAX_VALUE),"snapshotId",uuid(),"repoId",integer(1,Long.MAX_VALUE),"pullNumber",integer(1,Integer.MAX_VALUE),"expectedHeadSha",Map.of("type","string","pattern","^(?:[a-f0-9]{40}|[a-f0-9]{64})$"),"idempotencyKey",Map.of("type","string","minLength",1,"maxLength",128)),"projectId","snapshotId","repoId","pullNumber","expectedHeadSha","idempotencyKey"),
        definition("get_job","작업 종류, 상태와 고정 기준의 결과를 조회합니다. 문서 검증과 PR 검증을 구분하세요.",true,properties("projectId",integer(1,Long.MAX_VALUE),"jobId",uuid(),"cursor",string()),"projectId","jobId")
    );
    public List<Definition> definitions() {return DEFINITIONS;}
    public JsonNode invoke(String name,IntegrationPrincipal actor,Map<String,Object> arguments) {
        if(actor==null) throw new SecurityException("AUTHENTICATION_REQUIRED");
        var definition=DEFINITIONS.stream().filter(d->d.name().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_TOOL"));
        var input=mapper.valueToTree(arguments);validate(definition,input);
        if(name.equals("timiroom_list_projects")) return list(actor,input);
        Long project=input.path("projectId").asLong();
        if(name.equals("timiroom_get_job")) return job(actor,project,input);
        IntegrationScope scope=switch(name) {
            case "timiroom_propose_spec_change" -> IntegrationScope.SPECS_PROPOSE;
            case "timiroom_start_artifact_review","timiroom_start_consistency_check" -> IntegrationScope.CONSISTENCY_RUN;
            case "timiroom_get_project_context" -> IntegrationScope.PROJECTS_READ;
            default -> IntegrationScope.SPECS_READ;
        };
        access.require(actor,project,scope);
        return switch(name) {
            case "timiroom_get_project_context" -> context(actor,project);
            case "timiroom_get_spec_manifest" -> metadata(snapshot(actor,project,input));
            case "timiroom_read_spec" -> read(actor,project,input);
            case "timiroom_get_change_impact" -> impact(actor,project,input);
            case "timiroom_get_spec_change" -> proposal(actor,project,input);
            case "timiroom_propose_spec_change" -> mapper.valueToTree(tasks.beginChange(project,actor.memberId(),id(input,"snapshotId"),
                new ChangeInstruction(strings(input,"targetDocumentTypes").stream().map(ArtifactType::valueOf).toList(),input.path("instruction").asText(),strings(input,"constraints")),input.path("idempotencyKey").asText()));
            case "timiroom_start_artifact_review" -> receipt(tasks.beginArtifactReview(project,actor.memberId(),id(input,"proposalId"),input.path("proposalRevision").asInt(),input.path("idempotencyKey").asText()));
            case "timiroom_start_consistency_check" -> receipt(tasks.beginPullRequestReview(project,actor.memberId(),id(input,"snapshotId"),input.path("repoId").asLong(),input.path("pullNumber").asInt(),input.path("expectedHeadSha").asText(),input.path("idempotencyKey").asText()));
            default -> throw new IllegalArgumentException("UNKNOWN_TOOL");
        };
    }
    private JsonNode receipt(com.timiroom.domain.integrationjob.dto.JobDto job) {
        return mapper.createObjectNode().put("jobId",job.jobId().toString()).put("kind",job.kind().name())
            .put("status",job.status().name()).put("bindingKey",job.bindingKey()).put("attempts",job.attempts());
    }
    private JsonNode list(IntegrationPrincipal actor,JsonNode input) {
        var grant=access.requireGrant(actor,IntegrationScope.PROJECTS_READ);var items=mapper.createArrayNode();
        for(Long id:grant.projects().stream().sorted().toList()) {
            try {
                var project=documents.requireRead(id,actor.memberId());
                var node=items.addObject().put("projectId",id).put("name",project.getProjectName());node.set("repositories",repositories(id));
                try {node.put("publishedRevision",snapshots.latest(id,actor.memberId()).revision());}
                catch(IllegalStateException absent) {if(!absent.getMessage().startsWith("SPEC_NOT_PUBLISHED")) throw absent;node.putNull("publishedRevision");}
            } catch(SecurityException revokedProject) { /* A connection does not restore lost team membership. */ }
        }
        String binding="projects:"+DocumentHash.of(items.toString());int offset=pages.offset(actor,binding,cursor(input),items.size());
        int end=Math.min(items.size(),offset+input.path("limit").asInt(20));var selected=mapper.createArrayNode();
        for(int i=offset;i<end;i++) selected.add(items.get(i));
        var result=mapper.createObjectNode();result.set("projects",selected);result.put("nextCursor",pages.next(actor,binding,end,items.size()));return result;
    }
    private JsonNode context(IntegrationPrincipal actor,Long project) {
        var value=documents.requireRead(project,actor.memberId());var result=mapper.createObjectNode().put("projectId",project)
            .put("name",value.getProjectName()).put("description",value.getDescription());
        result.put("role",members.findByProjectIdAndMemberId(project,actor.memberId()).map(m->m.getProjectRole().name()).orElse("TEAM_MEMBER"));
        result.set("repositories",repositories(project));
        try {var published=metadata(snapshots.latest(project,actor.memberId()));published.remove("documents");result.set("publishedSpec",published);}
        catch(IllegalStateException absent) {if(!absent.getMessage().startsWith("SPEC_NOT_PUBLISHED")) throw absent;result.putNull("publishedSpec");}
        return result;
    }
    private ArrayNode repositories(Long project) {
        var result=mapper.createArrayNode();
        for(var link:repoLinks.findByProjectId(project)) repos.findById(link.getGithubRepoId()).ifPresent(repo->
            result.addObject().put("repoId",repo.getId()).put("repository",repo.getFullName()));
        return result;
    }
    private SpecSnapshotDto snapshot(IntegrationPrincipal actor,Long project,JsonNode input) {
        return input.has("snapshotId")?snapshots.get(project,actor.memberId(),id(input,"snapshotId")):snapshots.latest(project,actor.memberId());
    }
    private ObjectNode metadata(SpecSnapshotDto snapshot) {
        var result=mapper.createObjectNode().put("snapshotId",snapshot.snapshotId().toString()).put("projectId",snapshot.projectId())
            .put("revision",snapshot.revision()).put("publishedAt",snapshot.publishedAt().toString());
        var documents=result.putArray("documents");
        for(var doc:snapshot.documents()) {
            var item=documents.addObject().put("documentType",doc.type().name()).put("artifactId",doc.artifactId())
                .put("executionId",doc.executionId()).put("version",doc.version()).put("hash",doc.hash()).put("chars",doc.content().length());
            // 처음 생성된 프로젝트의 기능 목록은 이름만 있다. 기능 설명·요구사항은 PRD에 있다고 알려 준다.
            if(doc.type()==ArtifactType.FEATURE_LIST && namesOnlyFeatureList(doc.content()))
                item.put("note","기능 이름만 들어 있습니다. 기능 설명과 요구사항은 같은 기준의 PRD 문서 coreFeatures를 읽으세요.");
        }
        return result;
    }
    private JsonNode read(IntegrationPrincipal actor,Long project,JsonNode input) {
        var snapshot=snapshot(actor,project,input);var type=ArtifactType.valueOf(input.path("documentType").asText());
        var doc=snapshot.documents().stream().filter(d->d.type()==type).findFirst().orElseThrow(()->new IllegalArgumentException("DOCUMENT_NOT_FOUND"));
        String binding=project+":"+snapshot.snapshotId()+":"+type+":"+doc.hash();
        var result=mapper.valueToTree(pages.page(actor,binding,doc.content(),cursor(input),input.path("maxChars").asInt(12000)));
        ObjectNode node=(ObjectNode)result;node.put("snapshotId",snapshot.snapshotId().toString()).put("documentType",type.name()).put("hash",doc.hash()).put("version",doc.version());return node;
    }
    private JsonNode impact(IntegrationPrincipal actor,Long project,JsonNode input) {
        var snapshot=snapshot(actor,project,input);var before=DocumentBundle.from(snapshot);var after=before;
        JsonNode semanticImpact=mapper.nullNode();
        if(input.has("proposalId")) {
            var proposal=changes.view(project,actor.memberId(),id(input,"proposalId"));
            if(!snapshot.snapshotId().equals(proposal.snapshotId())) throw new IllegalStateException("SPEC_CONFLICT");
            semanticImpact=proposal.impact();
            after=new DocumentBundle(project,snapshot.snapshotId(),before.documents().stream().map(doc->new SpecDocumentDto(doc.type(),doc.artifactId(),doc.executionId(),doc.version(),doc.hash(),
                proposal.documents().has(doc.type().name())?proposal.documents().get(doc.type().name()).toString():doc.content())).toList());
        }
        var touchPoints=new ArrayList<com.timiroom.domain.graph.dto.PrTouchPoint>();var prSources=mapper.createArrayNode();
        if(actor.scopes().contains(IntegrationScope.CONSISTENCY_READ.value())) {
            access.require(actor,project,IntegrationScope.CONSISTENCY_READ);
            for(var job:jobRepository.findTop50ByProjectIdAndKindAndStateOrderByCreatedAtDesc(project,IntegrationJob.Kind.PR_REVIEW,IntegrationJob.State.COMPLETED)) {
                try {
                    var request=mapper.readTree(job.getRequestJson());var result=mapper.readTree(job.getResultJson());
                    if(!snapshot.snapshotId().toString().equals(result.path("snapshotId").asText())
                        || !pullRequests.evaluatorVersion().equals(request.path("evaluatorVersion").asText())) continue;
                    if(result.path("touchPoints").isArray()) for(var point:result.get("touchPoints"))
                        touchPoints.add(mapper.treeToValue(point,com.timiroom.domain.graph.dto.PrTouchPoint.class));
                    prSources.addObject().put("jobId",job.getJobId().toString()).put("snapshotId",snapshot.snapshotId().toString())
                        .put("headSha",result.path("headSha").asText()).put("baseSha",result.path("baseSha").asText())
                        .put("historical",true).put("currentHeadVerified",false);
                } catch(java.io.IOException invalidStoredRecord) {throw new IllegalStateException("INVALID_JOB_RESULT");}
            }
        }
        var calculated=graph.calculate(before,after,touchPoints);var focus=strings(input,"focusNodeIds");
        if(!focus.isEmpty()) calculated=focus(calculated,focus);
        var result=mapper.createObjectNode().put("maxImpactDepth",2).put("analysisMethod","TIMIROOM_DOCUMENT_GRAPH")
            .put("focusApplied",!focus.isEmpty()).put("summaryScope","FULL_SNAPSHOT");
        result.set("source",metadata(snapshot));result.set("graph",mapper.valueToTree(calculated));
        result.set("semanticImpact",semanticImpact);result.set("prReviewSources",prSources);
        return jsonPage(actor,"impact:"+DocumentHash.of(result.toString()),result,cursor(input));
    }
    private GraphResponse focus(GraphResponse full,List<String> roots) {
        var ids=new HashSet<String>();full.nodes().forEach(n->ids.add(n.id()));if(!ids.containsAll(roots)) throw new IllegalArgumentException("INVALID_FOCUS_NODE");
        var selected=new LinkedHashSet<>(roots);
        for(int depth=0;depth<2;depth++) {var neighbors=new HashSet<String>();for(var edge:full.edges()) {
            if(selected.contains(edge.source())) neighbors.add(edge.target());if(selected.contains(edge.target())) neighbors.add(edge.source());
        }selected.addAll(neighbors);}
        full.nodes().stream().filter(n->selected.contains(n.id())&&n.parent()!=null).map(GraphResponse.Node::parent).toList().forEach(selected::add);
        return new GraphResponse(full.nodes().stream().filter(n->selected.contains(n.id())).toList(),full.edges().stream().filter(e->selected.contains(e.source())&&selected.contains(e.target())).toList(),full.summary());
    }
    /**
     * 변경안 조회. 요약(상태·바뀐 문서·diff·영향·승인 링크)을 앞에 두고, 문서 원문은 documentType을 준 경우에만 싣는다.
     * 예전에는 기준 문서 4개와 변경안 원문을 통째로 실어 응답이 20만 자를 넘었고, 요약이 맨 뒤에 있어
     * AI 도구가 쪽을 여러 번 넘겨야 상태를 알 수 있었다.
     */
    private JsonNode proposal(IntegrationPrincipal actor,Long project,JsonNode input) {
        var proposal=changes.view(project,actor.memberId(),id(input,"proposalId"));
        boolean canReadReview=actor.scopes().contains(IntegrationScope.CONSISTENCY_READ.value());
        if(canReadReview) access.require(actor,project,IntegrationScope.CONSISTENCY_READ);
        String type=input.has("documentType")?input.path("documentType").asText():null;
        var result=mapper.createObjectNode().put("proposalId",proposal.proposalId().toString()).put("status",proposal.status().name())
            .put("proposalRevision",proposal.proposalRevision()).put("snapshotId",proposal.snapshotId().toString())
            .put("baseRevision",proposal.base().revision()).put("stale",proposal.stale());
        var changed=result.putArray("changedDocumentTypes");
        if(proposal.documents()!=null) proposal.documents().fieldNames().forEachRemaining(changed::add);
        if(frontend!=null) result.put("approvalUrl",frontend.replaceAll("/+$","")+"/spec-review?projectId="+project+"&proposalId="+proposal.proposalId());
        if(canReadReview) {
            result.put("artifactReviewPassed",proposal.artifactReviewPassed());
            result.set("artifactReview",proposal.artifactReview());
        }
        var diffs=mapper.createArrayNode();
        if(proposal.diffs()!=null && proposal.diffs().isArray()) for(var diff:proposal.diffs()) if(type==null || type.equals(diff.path("type").asText())) diffs.add(diff);
        result.set("diffs",diffs);
        result.set("impact",proposal.impact());
        var base=result.putObject("base").put("snapshotId",proposal.base().snapshotId().toString()).put("revision",proposal.base().revision());
        var baseDocs=base.putArray("documents");
        for(var doc:proposal.base().documents()) {
            var item=baseDocs.addObject().put("type",doc.type().name()).put("version",doc.version()).put("hash",doc.hash()).put("chars",doc.content().length());
            if(doc.type().name().equals(type)) item.put("content",doc.content());
        }
        if(type!=null) {
            var selected=result.putObject("documents");
            if(proposal.documents()!=null && proposal.documents().has(type)) selected.set(type,proposal.documents().get(type));
        } else {
            result.put("documentsHint","문서 원문(기준·변경안)이 필요하면 documentType을 지정해 다시 조회하세요.");
        }
        return jsonPage(actor,"proposal:"+DocumentHash.of(result.toString()),result,cursor(input));
    }
    private JsonNode job(IntegrationPrincipal actor,Long project,JsonNode input) {
        access.require(actor,project,actor.scopes().contains(IntegrationScope.SPECS_READ.value())?IntegrationScope.SPECS_READ:IntegrationScope.CONSISTENCY_READ);
        var job=jobs.get(project,actor.memberId(),id(input,"jobId"));
        access.require(actor,project,job.kind()==IntegrationJob.Kind.SPEC_CHANGE?IntegrationScope.SPECS_READ:IntegrationScope.CONSISTENCY_READ);
        var result=(ObjectNode)mapper.valueToTree(job);
        var stored=jobRepository.findById(job.jobId()).orElseThrow(()->new IllegalArgumentException("JOB_NOT_FOUND"));
        boolean stale=false;String freshness="CURRENT";
        try {
            var request=mapper.readTree(stored.getRequestJson());UUID baseline;
            if(job.kind()==IntegrationJob.Kind.ARTIFACT_REVIEW) {
                var proposal=changes.get(project,actor.memberId(),id(request,"proposalId"));baseline=proposal.getSnapshotId();
                stale=proposal.getProposalRevision()!=request.path("proposalRevision").asInt()
                    || !Objects.equals(proposal.getResultHash(),request.path("resultHash").asText());
            } else baseline=id(request,"snapshotId");
            stale=stale || !snapshots.latest(project,actor.memberId()).snapshotId().equals(baseline);
            if(job.kind()==IntegrationJob.Kind.PR_REVIEW) {
                stale=stale || !pullRequests.evaluatorVersion().equals(request.path("evaluatorVersion").asText());
                if(!stale) {
                    try {var current=pullRequests.pinPullRequest(project,actor.memberId(),request.path("repoId").asLong(),request.path("pullNumber").asInt(),request.path("expectedHeadSha").asText());
                        stale=!current.baseSha().equals(request.path("expectedBaseSha").asText());
                    } catch(IllegalStateException changed) {
                        if("PR_CHANGED".equals(changed.getMessage())) stale=true;
                        else {stale=true;freshness="UNCONFIRMED";}
                    }
                }
            }
        } catch(java.io.IOException invalidStoredRequest) {throw new IllegalStateException("Invalid stored job request",invalidStoredRequest);}
        if(stale && !"UNCONFIRMED".equals(freshness)) freshness="STALE";
        result.put("stale",stale).put("freshness",freshness);
        return jsonPage(actor,"job:"+DocumentHash.of(result.toString()),result,cursor(input));
    }
    private JsonNode jsonPage(IntegrationPrincipal actor,String binding,JsonNode value,String cursor) {
        var result=(ObjectNode)mapper.valueToTree(pages.page(actor,binding,value.toString(),cursor,12000));
        result.put("contentFormat","json").put("contentHash",DocumentHash.of(value.toString()));return result;
    }
    private String cursor(JsonNode input) {return input.has("cursor")?input.get("cursor").asText():null;}
    private UUID id(JsonNode input,String key) {return UUID.fromString(input.path(key).asText());}
    private List<String> strings(JsonNode input,String key) {
        var result=new ArrayList<String>();if(input.has(key)) input.get(key).forEach(n->result.add(n.asText()));return result;
    }
    private void validate(Definition definition,JsonNode input) {
        if(!input.isObject()) throw new IllegalArgumentException("INVALID_INPUT");
        for(var required:definition.schema().required()) if(!input.hasNonNull(required)) throw new IllegalArgumentException("INVALID_INPUT");
        input.fields().forEachRemaining(field->{var schema=definition.schema().properties().get(field.getKey());
            if(schema==null) throw new IllegalArgumentException("INVALID_INPUT");validateValue(field.getValue(),mapper.valueToTree(schema));});
    }
    private void validateValue(JsonNode value,JsonNode schema) {
        switch(schema.path("type").asText()) {
            case "string" -> {
                if(!value.isTextual() || value.asText().length()<schema.path("minLength").asInt(0) || value.asText().length()>schema.path("maxLength").asInt(24000)) throw new IllegalArgumentException("INVALID_INPUT");
                if("uuid".equals(schema.path("format").asText()) && !value.asText().matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw new IllegalArgumentException("INVALID_INPUT");
                if(schema.has("pattern")&&!value.asText().matches(schema.get("pattern").asText())) throw new IllegalArgumentException("INVALID_INPUT");
                if(schema.has("enum")) {boolean found=false;for(var allowed:schema.get("enum")) if(allowed.equals(value)) found=true;if(!found) throw new IllegalArgumentException("INVALID_INPUT");}
            }
            case "integer" -> {if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<schema.path("minimum").asLong()||value.asLong()>schema.path("maximum").asLong()) throw new IllegalArgumentException("INVALID_INPUT");}
            case "array" -> {
                if(!value.isArray()||value.size()<schema.path("minItems").asInt()||value.size()>schema.path("maxItems").asInt()) throw new IllegalArgumentException("INVALID_INPUT");
                var seen=new HashSet<JsonNode>();for(var item:value) {validateValue(item,schema.get("items"));if(!seen.add(item)) throw new IllegalArgumentException("INVALID_INPUT");}
            }
            default -> throw new IllegalArgumentException("INVALID_INPUT");
        }
    }
    private boolean namesOnlyFeatureList(String content) {
        try {return com.timiroom.domain.spec.service.FeatureListPromotion.namesOnly(mapper.readTree(content));}
        catch(Exception unreadable) {return false;}
    }
}
