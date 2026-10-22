package com.timiroom.domain.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.timiroom.domain.spec.service.FeatureListPromotion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureListPromotionTest {
    final ObjectMapper mapper = new ObjectMapper();
    final String prd = "{\"coreFeatures\":[{\"name\":\"포스터 일정 자동 추출\",\"requirements\":[\"OCR\"]},{\"name\":\"OCR 실패 재시도\"}]}";

    @Test void 이름만_있는_기능목록은_PRD_핵심기능으로_채운다() {
        var promoted = FeatureListPromotion.promote("[\"포스터 일정 자동 추출\"]", null, prd, mapper);
        assertThat(promoted).isPresent();
        assertThat(promoted.get()).contains("OCR 실패 재시도").contains("requirements");
    }

    @Test void 기능_상세가_있으면_그것을_먼저_쓴다() {
        var spec = "{\"features\":[{\"name\":\"상세 기능\",\"description\":\"설명\"}]}";
        assertThat(FeatureListPromotion.promote("{\"featureList\":[\"a\"]}", spec, prd, mapper)).hasValueSatisfying(v -> assertThat(v).contains("상세 기능").doesNotContain("OCR 실패 재시도"));
    }

    @Test void 이미_상세가_저장된_기능목록이나_채울_내용이_없으면_그대로_둔다() {
        assertThat(FeatureListPromotion.promote("[{\"name\":\"수정한 기능\"}]", null, prd, mapper)).isEmpty();
        assertThat(FeatureListPromotion.promote("[\"a\"]", null, "{\"coreFeatures\":[]}", mapper)).isEmpty();
        assertThat(FeatureListPromotion.promote("깨진 JSON", null, prd, mapper)).isEmpty();
    }
}
