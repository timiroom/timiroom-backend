# 기능 우선순위화 (MoSCoW + RICE)

**MoSCoW 프레임워크**:
- **Must Have**: MVP에 없으면 서비스 자체가 불가능한 기능
- **Should Have**: 중요하지만 MVP 없이도 출시 가능한 기능
- **Could Have**: 있으면 좋지만 우선순위 낮음
- **Won't Have**: 이번 버전에서 명시적으로 제외

**RICE 점수화** (우선순위 내 세부 순서 결정):
- Reach: 이 기능이 영향을 미치는 사용자 수/월
- Impact: 사용자에게 미치는 영향 (3=대, 2=중, 1=소, 0.5=미미)
- Confidence: 추정치에 대한 확신 (100%=높음, 80%=중, 50%=낮음)
- Effort: 개발에 필요한 인월(person-months)
- RICE Score = (Reach × Impact × Confidence) / Effort

**우선순위 결정 기준**:
1. 핵심 사용자 문제를 직접 해결하는가?
2. 비즈니스 목표(KPI)에 직접 기여하는가?
3. 기술적 의존성이 있는가? (선행 개발 필요 여부)
4. 개발 복잡도 대비 사용자 가치는?

**anti-pattern**:
- 모든 기능을 Must Have로 분류
- HiPPO (최고위직 의견) 기반 우선순위
- 데이터 없이 직관으로만 결정
