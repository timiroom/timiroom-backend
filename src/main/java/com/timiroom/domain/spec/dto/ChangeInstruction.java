package com.timiroom.domain.spec.dto;
import com.timiroom.domain.pipeline.entity.PipelineArtifact.ArtifactType;
import java.util.List;
public record ChangeInstruction(List<ArtifactType> targets, String instruction, List<String> constraints) {
    public ChangeInstruction {
        targets = List.copyOf(targets);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        if (targets.isEmpty() || targets.size()>4 || targets.stream().distinct().count()!=targets.size()
                || constraints.size()>20 || constraints.stream().anyMatch(c->c==null || c.length()>1000)
                || instruction==null || instruction.isBlank() || instruction.length()>8000)
            throw new IllegalArgumentException("수정 대상과 요청을 확인해주세요");
    }
}
