-- 홈 영상 자동 교체의 실행 기록 한 줄. 일정을 메모리 대신 여기에 두어 재배포와 서버 두 대에서도 주기가 이어진다.
CREATE TABLE pawbridge_community.home_video_curation (
    id smallint PRIMARY KEY CHECK (id = 1),
    last_succeeded_at timestamptz,
    last_attempted_at timestamptz
);
INSERT INTO pawbridge_community.home_video_curation(id) VALUES (1);
