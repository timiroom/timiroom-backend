package com.timiroom.domain.spec.dto;
import java.util.UUID;
/** 발행을 일으킨 승인 변경안. 직접 발행이면 null로 둔다. */
public record ProposalRef(UUID id,int revision,String resultHash) {}
