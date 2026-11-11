-- 홈 영상 자동 검색으로 만난 영상마다 한 줄: 판정 결과(예비 후보, 탈락)와 홈에 올린 기록.
-- YouTube API로 받은 자료라 checked_at부터 30일 안에 다시 확인해 갱신하거나 지운다. 제목, 설명, 판정 이유는 두지 않는다.
CREATE TABLE pawbridge_community.home_video_candidates (
    video_id varchar(11) PRIMARY KEY CHECK (video_id ~ '^[A-Za-z0-9_-]{11}$'),
    channel_id varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('WAITING', 'REJECTED', 'USED')),
    search_rank integer NOT NULL CHECK (search_rank >= 0),
    checked_at timestamptz NOT NULL
);
CREATE INDEX idx_home_video_candidates_checked ON pawbridge_community.home_video_candidates(checked_at);
