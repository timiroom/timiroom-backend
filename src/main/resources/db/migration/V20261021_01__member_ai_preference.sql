-- 회원이 고른 AI 품질(빠름·균형·정밀). 없으면 빠름(LOW)으로 본다.
CREATE TABLE member_ai_preference (
    member_id BIGINT PRIMARY KEY,
    model_tier VARCHAR(10) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
