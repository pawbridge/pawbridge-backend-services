-- AI 입양 도우미 대화 분석용 세션 정보. 대화를 시작할 때 한 번 정하고 이후 질문에서는 바꾸지 않는다.
-- member_id: 로그인한 상태로 시작한 대화의 회원 번호. 비로그인이면 NULL이고, 회원 삭제 때 NULL로 지운다.
--            pawbridge_user.users와 스키마가 달라 외래 키는 두지 않는다(즐겨찾기 수, 주문과 같은 방식).
-- device_type: User-Agent로 나눈 MOBILE 또는 DESKTOP. User-Agent 원문은 저장하지 않는다. 알 수 없으면 NULL.
-- 이 파일을 적용한 뒤에 이 칸을 쓰는 애플리케이션을 배포한다(먼저 배포하면 세션 저장과 회원 삭제가 실패한다).
ALTER TABLE chatbot_sessions
    ADD COLUMN member_id bigint DEFAULT NULL,
    ADD COLUMN device_type varchar(10) DEFAULT NULL;

-- 회원 삭제가 그 회원의 세션만 찾아 회원 번호를 지운다. 비로그인 세션이 대부분이라 부분 인덱스로 둔다.
CREATE INDEX idx_chatbot_sessions_member ON chatbot_sessions (member_id) WHERE member_id IS NOT NULL;
