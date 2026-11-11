-- Privileges of the single application role (pawbridge_app) in the contract tests.
--
-- Column lists are deliberate: REVOKE of a column privilege only has an effect when the role was granted
-- that column and not the whole table, and the permission contracts revoke single columns.
-- Add the grants a new contract needs here, with the narrowest list that lets the application work.

GRANT USAGE ON SCHEMA pawbridge_user TO pawbridge_app;
GRANT USAGE ON SCHEMA pawbridge_community TO pawbridge_app;

-- users: what the refresh rotation reads for its access-token claims. UPDATE on one column is the
-- minimum PostgreSQL requires for the SELECT ... FOR UPDATE row lock taken when a session is created.
-- DELETE backs the contract that deleting an account cascades to its sessions.
-- provider and created_at are read for the profile; UPDATE (nickname) with updated_at is the nickname change.
GRANT SELECT (user_id, nickname, role, care_reg_no, email, name, provider, created_at) ON pawbridge_user.users TO pawbridge_app;
GRANT UPDATE (nickname, updated_at) ON pawbridge_user.users TO pawbridge_app;
GRANT DELETE ON pawbridge_user.users TO pawbridge_app;

GRANT SELECT (session_id, user_id, token_hash, rotated_at, created_at, last_used_at, expires_at,
              absolute_expires_at, revoked_at) ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT INSERT (user_id, token_hash, created_at, last_used_at, expires_at, absolute_expires_at)
    ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT UPDATE (token_hash, rotated_at, last_used_at, expires_at, revoked_at)
    ON pawbridge_user.auth_sessions TO pawbridge_app;
GRANT DELETE ON pawbridge_user.auth_sessions TO pawbridge_app;

GRANT SELECT (token_hash, session_id, rotated_at) ON pawbridge_user.auth_session_token_history TO pawbridge_app;
GRANT INSERT (token_hash, session_id, rotated_at) ON pawbridge_user.auth_session_token_history TO pawbridge_app;

-- favorites: the status check reads the pair only.
GRANT SELECT (user_id, animal_id) ON pawbridge_user.favorites TO pawbridge_app;

-- User repositories (signup, local and social login, password, admin update, shelter promotion, directory).
-- users: login reads the password hash and the social lookup reads provider_id. Signup and social login insert the whole
-- account; password change, admin update and shelter approval update password, role and care_reg_no.
GRANT SELECT (password, provider_id) ON pawbridge_user.users TO pawbridge_app;
GRANT INSERT (email, name, nickname, password, role, care_reg_no, provider, provider_id, created_at, updated_at)
    ON pawbridge_user.users TO pawbridge_app;
GRANT UPDATE (password, role, care_reg_no) ON pawbridge_user.users TO pawbridge_app;

-- favorites: the favorite list reads id and time, add inserts and returns the id.
GRANT SELECT (favorite_id, created_at) ON pawbridge_user.favorites TO pawbridge_app;
GRANT INSERT (user_id, animal_id, created_at) ON pawbridge_user.favorites TO pawbridge_app;

-- shelter_applications: submit, own list, administrator list and detail, approve and reject.
GRANT SELECT (id, user_id, shelter_name, status, requested_at, reviewed_by, reviewed_at, review_note, care_reg_no)
    ON pawbridge_user.shelter_applications TO pawbridge_app;
GRANT INSERT (user_id, shelter_name, status, requested_at) ON pawbridge_user.shelter_applications TO pawbridge_app;
GRANT UPDATE (status, reviewed_by, reviewed_at, review_note, care_reg_no)
    ON pawbridge_user.shelter_applications TO pawbridge_app;

-- posts: list, detail, search, create (with the search projection), update and soft delete.
-- post_id comes from the identity column, so no sequence privilege is needed.
GRANT SELECT (post_id, author_id, title, content, board_type, image_urls, deleted_at, created_at, updated_at)
    ON pawbridge_community.posts TO pawbridge_app;
GRANT INSERT (author_id, title, content, board_type, image_urls, created_at, updated_at)
    ON pawbridge_community.posts TO pawbridge_app;
GRANT UPDATE (title, content, image_urls, deleted_at, updated_at) ON pawbridge_community.posts TO pawbridge_app;
-- The projection row is inserted on create, rewritten on update and removed on soft delete. SELECT is per column
-- so the search contract can revoke the one column (tokens) its query needs.
GRANT SELECT (post_id, title_hash, content_hash, analyzer_version, tokens)
    ON pawbridge_community.post_search_documents TO pawbridge_app;
GRANT INSERT, UPDATE, DELETE ON pawbridge_community.post_search_documents TO pawbridge_app;

-- comments: public list, create (RETURNING comment_id), content update and soft delete.
GRANT SELECT (comment_id, post_id, author_id, content, created_at, updated_at, deleted_at)
    ON pawbridge_community.comments TO pawbridge_app;
GRANT INSERT (post_id, author_id, content, created_at, updated_at) ON pawbridge_community.comments TO pawbridge_app;
GRANT UPDATE (content, deleted_at, updated_at) ON pawbridge_community.comments TO pawbridge_app;

-- home videos and daily visit totals are read-only.
GRANT SELECT (id, video_id, published, position, created_at, title, channel_title, thumbnail_url, duration_seconds,
              available, checked_at) ON pawbridge_community.home_videos TO pawbridge_app;
GRANT SELECT (visit_date, unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_app;

-- Private notes (blocks, mailboxes, send, stream). Every list is the union of what the reader and the writer of the
-- packaged-JAR fixtures were granted, and nothing wider: the permission contracts revoke single columns and tables.
-- Column grants stay column-level on purpose, so that REVOKE SELECT(body) or REVOKE INSERT(body) takes effect.
GRANT SELECT (user_id) ON pawbridge_user.contact_deletions TO pawbridge_app;

GRANT SELECT (member_id), INSERT (member_id), UPDATE (member_id)
    ON pawbridge_community.private_note_members TO pawbridge_app;

GRANT SELECT (member_id, blocked_id, created_at), INSERT (member_id, blocked_id, created_at)
    ON pawbridge_community.private_note_blocks TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_note_blocks TO pawbridge_app;

GRANT SELECT (note_id, sender_id, recipient_id, body, context_type, context_id, created_at, expires_at)
    ON pawbridge_community.private_notes TO pawbridge_app;
GRANT INSERT (note_id, sender_id, recipient_id, body, reply_to, context_type, context_id, created_at, expires_at)
    ON pawbridge_community.private_notes TO pawbridge_app;
GRANT UPDATE (note_id) ON pawbridge_community.private_notes TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_notes TO pawbridge_app;

GRANT SELECT (member_id, note_id, direction, read_at, favorite, created_at)
    ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT INSERT (member_id, note_id, direction, read_at, created_at)
    ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT UPDATE (read_at, favorite) ON pawbridge_community.private_note_mailboxes TO pawbridge_app;
GRANT DELETE ON pawbridge_community.private_note_mailboxes TO pawbridge_app;

GRANT SELECT (sender_id, request_id, request_hash, note_id, created_at),
      INSERT (sender_id, request_id, request_hash, note_id, created_at)
    ON pawbridge_community.private_note_requests TO pawbridge_app;

-- The context check of a note that answers an animal report reads the author of that report.
GRANT SELECT (report_id, author_id, deleted_at) ON pawbridge_community.animal_reports TO pawbridge_app;

-- Admin member deletion (pending marker, note and chat withdrawal, then the account and its favorites and shelter
-- applications). Same narrow lists as the writer of the packaged-JAR fixtures had; users keep no wider column grants.
GRANT INSERT (user_id) ON pawbridge_user.contact_deletions TO pawbridge_app;
GRANT DELETE ON pawbridge_user.favorites TO pawbridge_app;
GRANT SELECT (user_id), DELETE ON pawbridge_user.shelter_applications TO pawbridge_app;

GRANT DELETE ON pawbridge_community.private_note_members TO pawbridge_app;
GRANT UPDATE (context_type, context_id) ON pawbridge_community.private_notes TO pawbridge_app;
GRANT UPDATE (request_hash) ON pawbridge_community.private_note_requests TO pawbridge_app;

GRANT SELECT (room_id, first_member_id, second_member_id), UPDATE (first_member_id, second_member_id), DELETE
    ON pawbridge_community.member_chat_rooms TO pawbridge_app;
GRANT SELECT (room_id, member_id), DELETE ON pawbridge_community.member_chat_room_members TO pawbridge_app;
GRANT SELECT (sender_id), UPDATE (sender_id) ON pawbridge_community.member_chat_messages TO pawbridge_app;
GRANT SELECT (sender_id), DELETE ON pawbridge_community.member_chat_requests TO pawbridge_app;

-- Animal reports: public list and detail, author create, update and soft delete.
GRANT SELECT (report_id, author_id, report_kind, description, image_urls, occurred_on, approximate_time, region, landmark,
              species, animal_name, coat_color, animal_size, distinguishing_features, direction, created_at, updated_at,
              deleted_at, province, district, animal_type) ON pawbridge_community.animal_reports TO pawbridge_app;
GRANT INSERT (author_id, report_kind, description, image_urls, occurred_on, approximate_time, region, landmark, species,
              animal_name, coat_color, animal_size, distinguishing_features, direction, province, district, animal_type,
              created_at, updated_at) ON pawbridge_community.animal_reports TO pawbridge_app;
GRANT UPDATE (description, occurred_on, approximate_time, region, landmark, species, animal_name, coat_color, animal_size,
              distinguishing_features, direction, province, district, animal_type, updated_at, deleted_at)
    ON pawbridge_community.animal_reports TO pawbridge_app;

-- Home video management (board revision lock, selection, publication, order and metadata) and the daily refresh.
GRANT SELECT (id, revision), UPDATE (revision) ON pawbridge_community.home_video_board TO pawbridge_app;
GRANT INSERT (id, video_id, published, position, created_at) ON pawbridge_community.home_videos TO pawbridge_app;
GRANT UPDATE (video_id, published, position, title, channel_title, thumbnail_url, duration_seconds, available, checked_at)
    ON pawbridge_community.home_videos TO pawbridge_app;
-- 자동 교체: 새 영상은 메타데이터까지 한 번에 넣고(JPA INSERT는 모든 칸을 쓴다), 교체된 영상 줄은 지운다.
GRANT INSERT (title, channel_title, thumbnail_url, duration_seconds, available, checked_at),
      DELETE ON pawbridge_community.home_videos TO pawbridge_app;
GRANT SELECT (id, last_succeeded_at, last_attempted_at), UPDATE (last_succeeded_at, last_attempted_at)
    ON pawbridge_community.home_video_curation TO pawbridge_app;
-- 홈 영상 후보: 주간 판정 결과 저장과 30일 정리.
GRANT SELECT (video_id, channel_id, status, search_rank, checked_at),
      INSERT (video_id, channel_id, status, search_rank, checked_at),
      UPDATE (channel_id, status, search_rank, checked_at),
      DELETE ON pawbridge_community.home_video_candidates TO pawbridge_app;

-- Visit collection: one deduplicated row per visitor and day, the day total, the collection start and the daily cleanup.
GRANT INSERT (visit_date, unique_visitors), UPDATE (unique_visitors) ON pawbridge_community.daily_visit_count TO pawbridge_app;
GRANT SELECT (visit_date, visitor_hash), INSERT (visit_date, visitor_hash), DELETE ON pawbridge_community.daily_visitor TO pawbridge_app;
GRANT SELECT (singleton, started_at), INSERT (singleton, started_at) ON pawbridge_community.visit_collection TO pawbridge_app;

-- Member chat: rooms, memberships, messages and idempotent send requests.
GRANT SELECT (room_id, first_member_id, second_member_id, latest_sequence),
      INSERT (room_id, first_member_id, second_member_id), UPDATE (latest_sequence)
    ON pawbridge_community.member_chat_rooms TO pawbridge_app;
GRANT SELECT (room_id, member_id, read_through, hidden_through), INSERT (room_id, member_id),
      UPDATE (read_through, hidden_through) ON pawbridge_community.member_chat_room_members TO pawbridge_app;
GRANT SELECT (room_id, sequence, sender_id, body, context_type, context_id, created_at, expires_at),
      INSERT (room_id, sequence, sender_id, body, context_type, context_id, created_at, expires_at), DELETE
    ON pawbridge_community.member_chat_messages TO pawbridge_app;
GRANT SELECT (sender_id, request_id, request_hash, room_id, sequence, created_at),
      INSERT (sender_id, request_id, request_hash, room_id, sequence, created_at)
    ON pawbridge_community.member_chat_requests TO pawbridge_app;

-- Private note expiry removes old requests that never got a note.
GRANT DELETE ON pawbridge_community.private_note_requests TO pawbridge_app;

-- Chat expiry locks the rows it deletes with FOR UPDATE SKIP LOCKED, which PostgreSQL allows only with an UPDATE privilege.
GRANT DELETE ON pawbridge_community.member_chat_requests TO pawbridge_app;
GRANT UPDATE (request_hash) ON pawbridge_community.member_chat_requests TO pawbridge_app;

-- animal
-- The animal repositories read and write whole rows of their own tables (search projection, photo archive, travel
-- catalog, batch metadata, observations, favorite counts). The animal schema holds no private user data, so table-wide
-- privileges on every table of the schema are enough; no animal contract revokes single columns.
GRANT USAGE ON SCHEMA pawbridge_animal TO pawbridge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pawbridge_animal TO pawbridge_app;
-- The Spring Batch metadata ids come from three plain sequences (the other keys are identity columns and need no grant).
GRANT USAGE ON SEQUENCE pawbridge_animal.batch_job_seq, pawbridge_animal.batch_job_execution_seq,
    pawbridge_animal.batch_step_execution_seq TO pawbridge_app;

-- store
-- The store repositories read and write whole rows of their own tables (catalog, cart, orders, wishlists, search
-- documents). The store schema holds no private user data beyond numeric member ids, and no store contract revokes single
-- columns, so table-wide privileges (and the search view) are enough.
GRANT USAGE ON SCHEMA pawbridge_store TO pawbridge_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA pawbridge_store TO pawbridge_app;
