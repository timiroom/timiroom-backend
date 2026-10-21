package com.timiroom.domain.spec;

import com.timiroom.domain.spec.dto.ProposalRef;
import com.timiroom.domain.spec.dto.SpecDocumentDto;
import com.timiroom.domain.spec.service.SpecManifest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType.*;
import static org.assertj.core.api.Assertions.*;

class SpecManifestTest {
    static final UUID SNAPSHOT=UUID.fromString("8c1e0000-0000-4000-8000-000000000001");
    static final Instant AT=Instant.parse("2026-10-18T05:12:33.123456789Z");
    static final List<SpecDocumentDto> DOCS=List.of(
        new SpecDocumentDto(PRD,100L,9L,5,"ef","prd"),
        new SpecDocumentDto(API_SPEC,101L,9L,4,"ab","api"),
        new SpecDocumentDto(DB_SCHEMA,102L,9L,2,"cd","db"));

    // 지문 규칙이 바뀌면 이미 체인에 올라간 기록을 검증할 수 없게 된다. 이 값은 셸의 sha256sum으로 따로 계산했다.
    @Test void goldenManifestAndDigestStayStable() {
        var built=SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,null,null);
        assertThat(built.json()).isEqualTo("{\"documents\":[{\"artifactId\":101,\"hash\":\"ab\",\"type\":\"API_SPEC\",\"version\":4},"
            +"{\"artifactId\":102,\"hash\":\"cd\",\"type\":\"DB_SCHEMA\",\"version\":2},{\"artifactId\":100,\"hash\":\"ef\",\"type\":\"PRD\",\"version\":5}],"
            +"\"prevDigest\":null,\"projectId\":42,\"proposal\":null,\"publishedAt\":\"2026-10-18T05:12:33.123Z\",\"revision\":3,"
            +"\"snapshotId\":\"8c1e0000-0000-4000-8000-000000000001\",\"v\":1}");
        assertThat(built.digest()).isEqualTo("a739c75b59b631f93c39b1fff75503b898d50feef664f187e0933e42d1855174");
    }
    @Test void documentOrderDoesNotChangeDigest() {
        var shuffled=new ArrayList<>(DOCS); Collections.reverse(shuffled);
        assertThat(SpecManifest.build(42L,3,SNAPSHOT,AT,shuffled,null,null))
            .isEqualTo(SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,null,null));
    }
    @Test void timeAlwaysHasThreeFractionDigits() {
        assertThat(SpecManifest.time(Instant.parse("2026-10-18T05:12:33Z"))).isEqualTo("2026-10-18T05:12:33.000Z");
        assertThat(SpecManifest.time(Instant.parse("2026-10-18T05:12:33.999999Z"))).isEqualTo("2026-10-18T05:12:33.999Z");
    }
    @Test void proposalAndPreviousDigestAreBoundIntoDigest() {
        var ref=new ProposalRef(UUID.fromString("00000000-0000-4000-8000-0000000000aa"),2,"rh");
        var withProposal=SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,ref,"prev");
        assertThat(withProposal.json()).contains("\"prevDigest\":\"prev\"")
            .contains("\"proposal\":{\"id\":\"00000000-0000-4000-8000-0000000000aa\",\"resultHash\":\"rh\",\"revision\":2}");
        assertThat(withProposal.digest()).isNotEqualTo(SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,null,"prev").digest());
        assertThat(SpecManifest.proposal(SpecManifest.parse(withProposal.json()))).isEqualTo(ref);
        assertThat(SpecManifest.proposal(SpecManifest.parse(SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,null,null).json()))).isNull();
    }
    @Test void manifestCarriesNoPublisherId() {
        assertThat(SpecManifest.build(42L,3,SNAPSHOT,AT,DOCS,null,null).json()).doesNotContain("publishedBy").doesNotContain("actor");
    }
    @Test void projectKeyIsSha256OfPrefixedId() {
        assertThat(SpecManifest.projectKey(42L)).isEqualTo("1107bacca78eba4416ac0167db38a2fb8100f902b44b5c5779a765adf563280d");
    }
}
