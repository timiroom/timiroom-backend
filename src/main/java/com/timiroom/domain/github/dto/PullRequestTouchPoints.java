package com.timiroom.domain.github.dto;

import java.util.List;

/**
 * 한 PR이 건드린 접점.
 *
 * 지식 그래프에서 코드(PR)를 API·테이블에 잇는 근거가 된다.
 * 정합성 검사 시점에 변경 파일에서 뽑아 review record에 JSON으로 저장해 두고,
 * 그래프를 그릴 때는 이것만 읽는다.
 */
public record PullRequestTouchPoints(
        List<String> apis,
        List<String> tables,
        List<String> files
) {
    public PullRequestTouchPoints {
        apis = apis == null ? List.of() : List.copyOf(apis);
        tables = tables == null ? List.of() : List.copyOf(tables);
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static PullRequestTouchPoints empty() {
        return new PullRequestTouchPoints(List.of(), List.of(), List.of());
    }
}
