package com.timiroom.domain.billing.service;

import com.timiroom.domain.billing.entity.BillingPayment;
import com.timiroom.domain.member.repository.MemberRepository;
import com.timiroom.infra.mail.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/**
 * 결제·사용량 알림 메일. 문구는 짧게, 금액과 날짜와 할 일만 적는다.
 * 같은 알림은 {@link NoticeLog}로 한 번만 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingNotifier {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final MailService mail;
    private final MemberRepository members;
    private final NoticeLog notices;

    @Value("${notify.site-url:https://timiroom.kro.kr}")
    private String siteUrl;

    @Value("${notify.admin-emails:}")
    private String adminEmails;

    public void paymentSucceeded(BillingPayment p) {
        if (!notices.firstTime("PAID", p.getPaymentId())) return;
        send(p.getMemberId(), "[Align-it] 결제가 완료되었습니다",
                p.getOrderName() + "\n결제 금액: " + won(p.getAmountKrw())
                        + "\n결제 수단: " + (p.getCardSummary() != null ? p.getCardSummary() : "카드")
                        + "\n\n결제 확인서: " + siteUrl + "/receipt/" + p.getId());
    }

    public void renewalFailed(Long memberId, int attempts, int maxAttempts, String reason) {
        boolean last = attempts >= maxAttempts;
        send(memberId, last ? "[Align-it] 결제가 되지 않아 무료 요금제로 바뀌었습니다" : "[Align-it] 프로 요금제 결제에 실패했습니다",
                (reason != null ? "사유: " + reason + "\n" : "")
                        + (last ? "결제가 " + maxAttempts + "번 실패해 무료 요금제로 바꿨습니다. 다시 구독하면 바로 프로를 쓸 수 있습니다."
                                : "내일 다시 결제합니다. 카드 정보를 확인하거나 다른 카드를 기본으로 바꿔 주세요.")
                        + "\n\n결제 관리: " + siteUrl + "/settings/billing");
    }

    public void renewalUpcoming(Long memberId, Instant billingAt, long amountKrw) {
        if (!notices.firstTime("RENEWAL_SOON", memberId + ":" + billingAt)) return;
        send(memberId, "[Align-it] " + day(billingAt) + "에 프로 요금제가 자동 결제됩니다",
                day(billingAt) + "에 등록된 기본 카드로 " + won(amountKrw) + "이 결제됩니다."
                        + "\n원하지 않으면 그 전에 해지해 주세요. 해지해도 남은 기간은 그대로 쓸 수 있습니다."
                        + "\n\n결제 관리: " + siteUrl + "/settings/billing");
    }

    public void refunded(Long memberId, long amountKrw, String what) {
        send(memberId, "[Align-it] 환불이 접수되었습니다",
                what + " " + won(amountKrw) + " 환불을 카드사에 요청했습니다."
                        + "\n카드사에 따라 반영까지 3~7영업일이 걸릴 수 있습니다."
                        + "\n\n결제 내역: " + siteUrl + "/settings/billing");
    }

    /** 이번 기간 사용량이 80%, 100%에 닿았을 때 한 번씩 */
    public void usageReached(Long memberId, int threshold, Instant periodStart, Instant resetAt) {
        if (!notices.firstTime("USAGE_" + threshold, memberId + ":" + periodStart)) return;
        send(memberId, threshold >= 100 ? "[Align-it] 이번 달 AI 사용량을 모두 썼습니다" : "[Align-it] 이번 달 AI 사용량의 " + threshold + "%를 썼습니다",
                (threshold >= 100
                        ? "추가 사용량을 켜 두었으면 충전한 잔액으로 계속 쓸 수 있습니다. 아니면 " + day(resetAt) + "에 다시 채워집니다."
                        : day(resetAt) + "에 다시 채워집니다. 더 필요하면 프로 요금제나 추가 사용량을 이용해 주세요.")
                        + "\n\n사용량 보기: " + siteUrl + "/settings/usage");
    }

    /** 운영자에게. 하루에 한 번만 보낸다. */
    public void adminAlert(String kind, String ref, String subject, String body) {
        if (!notices.firstTime(kind, ref)) return;
        log.warn("운영 경보 | {} | {}", subject, body.replace('\n', ' '));
        for (String to : admins()) mail.send(to, "[Align-it 운영] " + subject, body);
    }

    private List<String> admins() {
        if (adminEmails == null || adminEmails.isBlank()) return List.of();
        return Arrays.stream(adminEmails.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void send(Long memberId, String subject, String body) {
        members.findById(memberId).ifPresent(m -> mail.send(m.getEmail(), subject,
                body + "\n\n이 메일은 발신 전용입니다."));
    }

    static String won(long amount) {
        return String.format("%,d원", amount);
    }

    private static String day(Instant at) {
        LocalDate d = LocalDate.ofInstant(at, ZONE);
        return d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
    }
}
