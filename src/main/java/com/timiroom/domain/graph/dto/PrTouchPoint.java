package com.timiroom.domain.graph.dto;
import com.timiroom.domain.github.dto.PullRequestTouchPoints;
public record PrTouchPoint(String id, String label, int pullNumber, String url, String reviewUrl,
                                   String repository, int score, String evaluator, int warnings,
                                   PullRequestTouchPoints touched, String headSha) {

        /**
         * diff에서 뽑은 경로가 이 엔드포인트를 가리키는지.
         *
         * 양쪽을 그대로 견주지 않는다. 코드에는 `/api/v1/reviews/{reviewId}`가 문자열
         * 상수로 쪼개져 있거나 클래스의 @RequestMapping과 메서드 매핑이 나뉘어 있어,
         * diff에서 건진 조각이 명세의 전체 경로와 정확히 같은 경우는 드물다.
         * 한쪽이 다른 쪽을 품고 있으면 같은 엔드포인트를 말하는 것으로 본다.
         */
        public boolean touchesApi(String specPath) {
            String spec = normalizePath(specPath);
            if (spec.isBlank()) return false;
            for (String candidate : touched.apis()) {
                String found = normalizePath(candidate);
                if (found.isBlank()) continue;
                if (spec.equals(found) || spec.startsWith(found) || found.startsWith(spec)) return true;
            }
            return false;
        }

        public boolean touchesTable(String tableName) {
            return touched.tables().stream().anyMatch(name -> {
                String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                return bare.equalsIgnoreCase(tableName);
            });
        }

        /** 경로 변수는 이름이 서로 달라도 같은 자리다 — {reviewId}와 {id}를 같게 본다 */
        private static String normalizePath(String path) {
            if (path == null) return "";
            return path.toLowerCase().replaceAll("\\{[^}]*}", "{}").replaceAll("/+$", "").trim();
        }
    }
