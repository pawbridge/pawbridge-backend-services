package com.pawbridge.backend.contract

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `DELETE /api/v1/admin/users/{id}` on the real application, PostgreSQL and real event streams (replaces the
 * packaged F31 member-cleanup smoke).
 *
 * The deletion runs in three committed steps: a pending marker (which also revokes the member's refresh sessions),
 * the note and chat withdrawal, and the account itself. A failure in a later step keeps the earlier committed steps
 * and rolls back only its own, and the same request can be repeated until it succeeds.
 *
 * Seeded for the member to delete ([caller]) and the member who stays ([peer]):
 * - an account, a favorite, a shelter application and a refresh session each;
 * - note A from the caller to the other member (both mailboxes, with a context link), note B from the other member
 *   to the caller (only the caller keeps it), three send requests, and a block in each direction;
 * - chat room 1 between both (two messages, one sent by the caller), a chat send request of the caller, and chat
 *   room 2 in which the caller is the only member;
 * - an AI adoption helper conversation each, started while logged in. Deleting the caller clears only the member id of
 *   the caller's conversation (the retryable cleanup step); the conversation itself stays until its retention ends.
 */
class MemberDeleteContractTest : NoteContractBase() {
    override val ids = 9_986_000L..9_986_999L

    /**
     * Every test gets its own pair of members. A stream whose client has gone is only noticed by the server at its
     * next write, and a member may hold three streams, so reusing the ids would make later tests depend on earlier ones.
     */
    private var caller = 0L
    private var peer = 0L

    /** The shelter and animal the chatbot conversations hang on; one per test like the members. */
    private var animal = 0L

    private val noteA = UUID.fromString("f3100000-0000-4000-8000-000000000001")
    private val noteB = UUID.fromString("f3100000-0000-4000-8000-000000000002")
    private val otherRequestA = UUID.fromString("f3100000-0000-4000-8000-000000000011")
    private val otherRequestB = UUID.fromString("f3100000-0000-4000-8000-000000000012")
    private val callerRequest = UUID.fromString("f3100000-0000-4000-8000-000000000013")
    private val sharedRoom = UUID.fromString("f3100000-0000-4000-8000-000000000021")
    private val soloRoom = UUID.fromString("f3100000-0000-4000-8000-000000000022")
    private val chatRequest = UUID.fromString("f3100000-0000-4000-8000-000000000023")

    @BeforeEach
    fun seedMemberData() {
        ContractFakeS3.reset()
        val pair = PAIRS.getAndIncrement()
        caller = ids.first + 2 * pair + 1
        peer = ids.first + 2 * pair + 2
        animal = ids.last - pair
        seedUser(caller, "F31caller$caller")
        seedUser(peer, "F31other$peer")
        seedMember(caller)
        seedMember(peer)
        seedAccountRows()
        seedNoteRows()
        seedChatRows()
        seedChatbotRows()
    }

    @AfterEach
    fun removeMemberRows() {
        val admin = ContractPostgres.admin
        admin.update("DELETE FROM pawbridge_community.member_chat_requests WHERE sender_id BETWEEN ? AND ?", ids.first, ids.last)
        admin.update("DELETE FROM pawbridge_community.member_chat_rooms WHERE room_id IN (?, ?)", sharedRoom, soloRoom)
        admin.update("DELETE FROM pawbridge_user.favorites WHERE user_id BETWEEN ? AND ?", ids.first, ids.last)
        admin.update("DELETE FROM pawbridge_user.shelter_applications WHERE user_id BETWEEN ? AND ?", ids.first, ids.last)
        admin.update("DELETE FROM pawbridge_animal.chatbot_sessions WHERE animal_id = ?", animal)
        admin.update("DELETE FROM pawbridge_animal.animals WHERE id = ?", animal)
        admin.update("DELETE FROM pawbridge_animal.shelters WHERE id = ?", animal)
    }

    private fun seedChatbotRows() {
        val admin = ContractPostgres.admin
        admin.update(
            "INSERT INTO pawbridge_animal.shelters(id, created_at, care_reg_no, name, address, phone) " +
                "VALUES (?, '2026-01-01 00:00:00', ?, 'F31 synthetic shelter', 'F31 synthetic address', '000-0000-0000')",
            animal, "F31-care-$animal",
        )
        admin.update(
            "INSERT INTO pawbridge_animal.animals(id, created_at, api_source, apms_notice_no, favorite_count, gender, " +
                "neuter_status, notice_end_date, notice_start_date, species, status, shelter_id) " +
                "VALUES (?, '2026-03-01 00:00:00', 'MANUAL', ?, 0, 'MALE', 'UNKNOWN', '2026-03-20', '2026-02-01', 'DOG', 'PROTECT', ?)",
            animal, "F31-$animal", animal,
        )
        for (member in listOf(caller, peer)) {
            admin.update(
                "INSERT INTO pawbridge_animal.chatbot_sessions(id, anonymous_session_id, animal_id, member_id, device_type, created_at) " +
                    "VALUES (?, ?, ?, ?, 'MOBILE', '2026-11-01 00:00:00')",
                chatbotSession(member), "f31-anonymous-$member", animal, member,
            )
        }
    }

    private fun chatbotSession(member: Long): String = "f31-chatbot-$member"

    /** The member id stored on a member's chatbot conversation; the row itself must still exist. */
    private fun chatbotMember(member: Long): String? {
        assertThat(count("SELECT count(*) FROM pawbridge_animal.chatbot_sessions WHERE id = ?", chatbotSession(member)))
            .`as`("The chatbot conversation is kept").isOne()
        return scalar("SELECT member_id::text FROM pawbridge_animal.chatbot_sessions WHERE id = ?", chatbotSession(member))
    }

    private fun seedAccountRows() {
        val admin = ContractPostgres.admin
        for ((index, member) in listOf(caller, peer).withIndex()) {
            admin.update(
                "INSERT INTO pawbridge_user.favorites(animal_id, created_at, user_id) VALUES (?, ?::timestamp, ?)",
                index + 1, "2026-10-18 15:00:00", member,
            )
            admin.update(
                "INSERT INTO pawbridge_user.shelter_applications(user_id, shelter_name, status, requested_at) " +
                    "VALUES (?, ?, 'PENDING', ?::timestamp)",
                member, "F31 synthetic shelter", "2026-10-18 15:00:00",
            )
            val tokenHash = (if (member == caller) "a" else "b").repeat(64)
            admin.update(
                "INSERT INTO pawbridge_user.auth_sessions(user_id, token_hash, created_at, last_used_at, expires_at, " +
                    "absolute_expires_at) VALUES (?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)",
                member, tokenHash, STORED_TIME, STORED_TIME, "2099-01-01T00:00:00Z", "2099-01-01T00:00:00Z",
            )
            secrets.add(tokenHash)
        }
    }

    private fun seedNoteRows() {
        seedNote(
            noteA, caller, peer, NOTE_BODY,
            senderBox = Mailbox(readAt = STORED_TIME), recipientBox = Mailbox(),
            context = "POST" to 123L,
        )
        seedNote(noteB, peer, caller, NOTE_BODY, recipientBox = Mailbox())
        for ((sender, request, note, hash) in listOf(
            RequestRow(peer, otherRequestA, noteA, "a".repeat(64)),
            RequestRow(peer, otherRequestB, noteB, "b".repeat(64)),
            RequestRow(caller, callerRequest, noteB, "c".repeat(64)),
        )) {
            ContractPostgres.admin.update(
                "INSERT INTO pawbridge_community.private_note_requests(sender_id, request_id, request_hash, note_id, created_at) " +
                    "VALUES (?, ?, ?, ?, ?::timestamptz)",
                sender, request, hash, note, STORED_TIME,
            )
        }
        seedBlock(caller, peer)
        seedBlock(peer, caller)
    }

    private fun seedChatRows() {
        val admin = ContractPostgres.admin
        admin.update(
            "INSERT INTO pawbridge_community.member_chat_rooms(room_id, first_member_id, second_member_id, latest_sequence) " +
                "VALUES (?, ?, ?, 2), (?, ?, NULL, 1)",
            sharedRoom, caller, peer, soloRoom, caller,
        )
        admin.update(
            "INSERT INTO pawbridge_community.member_chat_room_members(room_id, member_id) VALUES (?, ?), (?, ?), (?, ?)",
            sharedRoom, caller, sharedRoom, peer, soloRoom, caller,
        )
        for ((room, sequence, sender) in listOf(
            Triple(sharedRoom, 1, caller),
            Triple(sharedRoom, 2, peer),
            Triple(soloRoom, 1, caller),
        )) {
            admin.update(
                "INSERT INTO pawbridge_community.member_chat_messages(room_id, sequence, sender_id, body, created_at, expires_at) " +
                    "VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)",
                room, sequence, sender, CHAT_BODY, STORED_TIME, "2099-01-01T00:00:00Z",
            )
        }
        admin.update(
            "INSERT INTO pawbridge_community.member_chat_requests(sender_id, request_id, request_hash, room_id, sequence, created_at) " +
                "VALUES (?, ?, ?, ?, 1, ?::timestamptz)",
            caller, chatRequest, "d".repeat(64), sharedRoom, STORED_TIME,
        )
        secrets.add(CHAT_BODY)
        secrets.add(NOTE_BODY)
    }

    private fun deleteMember(token: String?, member: Long = caller) = call("DELETE", "$ADMIN_USERS/$member", token)

    private fun count(query: String, vararg args: Any?): Long = scalar(query, *args)!!.toLong()

    private fun accountRows(table: String, member: Long): Long =
        count("SELECT count(*) FROM pawbridge_user.$table WHERE user_id = ?", member)

    private fun pendingMarker(): Boolean = accountRows("contact_deletions", caller) == 1L

    private fun sessionRevoked(member: Long): Boolean = count(
        "SELECT count(*) FROM pawbridge_user.auth_sessions WHERE user_id = ? AND revoked_at IS NOT NULL", member,
    ) == 1L

    /** Account, favorite, shelter application and refresh session of one member all exist. */
    private fun accountComplete(member: Long): Boolean =
        listOf("users", "favorites", "shelter_applications", "auth_sessions").all { accountRows(it, member) == 1L }

    private fun callerCommunityKept(): Boolean =
        hasMember(caller) && noteExists(noteB) && mailboxExists(caller, noteA) && mailboxExists(caller, noteB) &&
            count("SELECT count(*) FROM pawbridge_community.private_note_blocks") == 2L &&
            count("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id = ?", caller) == 1L &&
            count("SELECT count(*) FROM pawbridge_community.member_chat_room_members WHERE member_id = ?", caller) == 2L &&
            count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", caller) == 1L &&
            count("SELECT count(*) FROM pawbridge_community.member_chat_messages WHERE sender_id = ?", caller) == 2L &&
            scalar("SELECT sender_id::text FROM pawbridge_community.private_notes WHERE note_id = ?", noteA) == caller.toString()

    /** What deleting the caller leaves behind in the community schema: the other member's side only. */
    private fun assertCommunityWithdrawn() {
        assertThat(hasMember(caller)).`as`("The caller's note membership").isFalse()
        assertThat(hasMember(peer)).isTrue()
        assertThat(mailboxExists(caller, noteA)).isFalse()
        assertThat(mailboxExists(caller, noteB)).isFalse()
        assertThat(mailboxExists(peer, noteA)).isTrue()
        assertThat(noteExists(noteB)).`as`("A note nobody keeps is deleted").isFalse()
        assertThat(noteExists(noteA)).`as`("The other member keeps the note").isTrue()
        val kept = row(
            "SELECT sender_id, recipient_id, context_type, context_id, body FROM pawbridge_community.private_notes WHERE note_id = ?",
            noteA,
        )
        assertThat(kept["sender_id"]).isNull()
        assertThat(kept["recipient_id"]).isEqualTo(peer)
        assertThat(kept["context_type"]).isNull()
        assertThat(kept["context_id"]).isNull()
        assertThat(kept["body"]).isEqualTo(NOTE_BODY)
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_blocks")).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.private_note_requests WHERE sender_id = ?", caller)).isZero()
        val requests = ContractPostgres.admin.queryForList(
            "SELECT request_id::text AS id, request_hash, note_id::text AS note FROM pawbridge_community.private_note_requests " +
                "WHERE sender_id = ? ORDER BY request_id",
            peer,
        )
        assertThat(requests.map { it["id"] }).containsExactly(otherRequestA.toString(), otherRequestB.toString())
        assertThat(requests.map { it["request_hash"] }).`as`("The stored request hashes are cleared").containsOnlyNulls()
        assertThat(requests.map { it["note"] }).containsExactly(noteA.toString(), null)

        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_requests WHERE sender_id = ?", caller)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_room_members WHERE member_id = ?", caller)).isZero()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_room_members WHERE member_id = ?", peer)).isOne()
        assertThat(count("SELECT count(*) FROM pawbridge_community.member_chat_rooms WHERE room_id = ?", soloRoom))
            .`as`("A chat room without members is deleted").isZero()
        val room = row(
            "SELECT first_member_id, second_member_id FROM pawbridge_community.member_chat_rooms WHERE room_id = ?", sharedRoom,
        )
        assertThat(room["first_member_id"]).isNull()
        assertThat(room["second_member_id"]).isEqualTo(peer)
        val messages = ContractPostgres.admin.queryForList(
            "SELECT sequence, sender_id FROM pawbridge_community.member_chat_messages WHERE room_id = ? ORDER BY sequence",
            sharedRoom,
        )
        assertThat(messages.map { it["sender_id"] }).`as`("Messages stay, the caller is unlinked").containsExactly(null, peer)
    }

    private fun assertCallerGone() {
        assertThat(listOf("users", "favorites", "shelter_applications", "auth_sessions", "contact_deletions").map { accountRows(it, caller) })
            .`as`("Account, favorite, shelter application, refresh session and pending marker of the caller")
            .containsOnly(0L)
        assertCommunityWithdrawn()
        assertThat(chatbotMember(caller)).`as`("The caller's chatbot conversation is no longer linked to the caller").isNull()
    }

    private fun assertOtherKept() {
        assertThat(accountComplete(peer)).`as`("The other member's account rows").isTrue()
        assertThat(sessionRevoked(peer)).`as`("The other member's session stays usable").isFalse()
        assertThat(count("SELECT count(*) FROM pawbridge_user.favorites WHERE user_id = ? AND animal_id = 2", peer)).isOne()
        assertThat(chatbotMember(peer)).isEqualTo(peer.toString())
    }

    private fun withdrawnView(): Map<String, Any?> = mapOf(
        "noteId" to noteA.toString(),
        "body" to NOTE_BODY,
        "direction" to "INBOX",
        "createdAt" to STORED_TIME,
        "readAt" to null,
        "favorite" to false,
        "counterpartId" to null,
        "counterpartNickname" to WITHDRAWN,
        "canReply" to false,
        "contextHref" to null,
    )

    @Test
    fun `토큰이 없을 때__회원을 삭제하면__401로 거부하고 아무것도 바뀌지 않으며 스트림도 조용하다`() {
        // given
        val victim = openStream(token(caller))
        val survivor = openStream(token(peer))
        victim.await("ready", 1)
        survivor.await("ready", 1)

        // when
        val reply = withoutWrites { deleteMember(null) }

        // then
        assertSecurity(reply, 401, "인증 토큰이 필요합니다.")
        assertThat(callerCommunityKept()).isTrue()
        assertThat(accountComplete(caller)).isTrue()
        victim.awaitExactly("resync", 0)
        survivor.awaitExactly("resync", 0)
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    @Test
    fun `관리자가 아닌 토큰일 때__회원을 삭제하면__위조 헤더와 무관하게 403이고 아무것도 바뀌지 않는다`() {
        // given
        val victim = openStream(token(caller))
        val survivor = openStream(token(peer))
        victim.await("ready", 1)
        survivor.await("ready", 1)

        // when
        val byOther = withoutWrites { deleteMember(token(peer)) }
        val byShelter = withoutWrites { deleteMember(token(peer, "ROLE_SHELTER")) }
        val byItself = withoutWrites { deleteMember(token(caller)) }

        // then
        for (reply in listOf(byOther, byShelter, byItself)) {
            assertSecurity(reply, 403, "관리자 권한이 필요합니다.")
        }
        assertThat(callerCommunityKept()).isTrue()
        assertThat(accountComplete(caller)).isTrue()
        victim.awaitExactly("resync", 0)
        survivor.awaitExactly("resync", 0)
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    @Test
    fun `없는 회원일 때__관리자가 삭제하면__400이고 아무것도 바뀌지 않는다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")

        // when
        val reply = withoutWrites { deleteMember(admin, ids.last) }

        // then
        assertSecurity(reply, 400, "존재하지 않는 회원입니다.")
        assertThat(callerCommunityKept()).isTrue()
    }

    @Test
    fun `모든 데이터가 있는 회원일 때__관리자가 삭제하면__연쇄 정리하고 상대 데이터는 남기며 스트림에 알린다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")
        val other = token(peer)
        val victim = openStream(token(caller))
        val survivor = openStream(other)
        victim.await("ready", 1)
        survivor.await("ready", 1)

        // when
        val (reply, changed) = observingChanges { deleteMember(admin) }

        // then
        assertSecurity(reply, 200, "회원이 삭제되었습니다.")
        assertThat(changed).containsExactlyInAnyOrder(*DELETION_TABLES)
        assertCallerGone()
        assertOtherKept()
        victim.awaitEnd()
        survivor.awaitExactly("resync", 1)
        assertOk(withoutWrites { get("$NOTES/$noteA", other) }, withdrawnView())
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    @Test
    fun `채팅 보낸이 변경 권한이 없을 때__삭제하면__503이고 대기 표식과 세션 폐기만 남으며 복구 후 재시도로 끝난다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")
        val victim = openStream(token(caller))
        val survivor = openStream(token(peer))
        victim.await("ready", 1)
        survivor.await("ready", 1)

        // when
        val (failed, changed) = ContractPostgres.withRevoked("UPDATE(sender_id)", "pawbridge_community.member_chat_messages") {
            observingChanges { deleteMember(admin) }
        }
        val pendingAfterFailure = pendingMarker()
        val communityUntouched = callerCommunityKept()
        val sessionRevokedAfterFailure = sessionRevoked(caller)
        val accountKept = accountRows("users", caller) == 1L
        victim.awaitExactly("resync", 0)
        survivor.awaitExactly("resync", 0)
        val (recovered, recoveredChanges) = observingChanges { deleteMember(admin) }

        // then
        assertSecurity(failed, 503, "쪽지 정리가 완료되지 않았습니다. 회원 삭제를 다시 시도해 주세요.")
        assertThat(changed).containsExactlyInAnyOrder("pawbridge_user.contact_deletions", "pawbridge_user.auth_sessions")
        assertThat(pendingAfterFailure).isTrue()
        assertThat(sessionRevokedAfterFailure).`as`("A member pending deletion cannot refresh").isTrue()
        assertThat(communityUntouched).`as`("The failed community cleanup rolled back completely").isTrue()
        assertThat(accountKept).isTrue()
        assertSecurity(recovered, 200, "회원이 삭제되었습니다.")
        assertThat(recoveredChanges).containsExactlyInAnyOrder(*DELETION_TABLES, "pawbridge_user.contact_deletions")
        assertCallerGone()
        assertOtherKept()
        victim.awaitEnd()
        survivor.awaitExactly("resync", 1)
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    @Test
    fun `계정 삭제 권한이 없을 때__삭제하면__500이고 쪽지 정리만 남으며 재접속은 403이고 복구 후 재시도로 끝난다`() {
        // given
        val admin = token(ADMIN, "ROLE_ADMIN")
        val victim = openStream(token(caller))
        val survivor = openStream(token(peer))
        victim.await("ready", 1)
        survivor.await("ready", 1)

        // when
        val (failed, changed) = ContractPostgres.withRevoked("DELETE", "pawbridge_user.users") {
            observingChanges { deleteMember(admin) }
        }
        val pendingAfterFailure = pendingMarker()
        val accountKept = accountComplete(caller) && accountRows("users", peer) == 1L
        victim.awaitEnd()
        survivor.awaitExactly("resync", 1)
        val reconnect = withoutWrites { get("$NOTES/stream", token(caller)) }
        val (retried, retryChanges) = observingChanges { deleteMember(admin) }

        // then
        assertSecurity(failed, 500, "서버 에러!")
        assertThat(changed).containsExactlyInAnyOrder(
            *COMMUNITY_TABLES,
            "pawbridge_animal.chatbot_sessions",
            "pawbridge_user.contact_deletions",
            "pawbridge_user.auth_sessions",
        )
        assertThat(pendingAfterFailure).isTrue()
        assertThat(accountKept).`as`("The failed account deletion rolled back").isTrue()
        assertCommunityWithdrawn()
        assertRejected(reconnect, 403, "이용할 수 없는 회원입니다.")
        assertSecurity(retried, 200, "회원이 삭제되었습니다.")
        assertThat(retryChanges).containsExactlyInAnyOrder(
            "pawbridge_user.users",
            "pawbridge_user.favorites",
            "pawbridge_user.shelter_applications",
            "pawbridge_user.contact_deletions",
            "pawbridge_user.auth_sessions",
        )
        assertCallerGone()
        assertOtherKept()
        survivor.awaitExactly("resync", 2)
        assertThat(ContractFakeS3.requests()).isEmpty()
    }

    private data class RequestRow(val sender: Long, val request: UUID, val note: UUID, val hash: String)

    private companion object {
        val PAIRS = AtomicInteger()
        const val NOTES = "/api/v1/notes"
        const val ADMIN_USERS = "/api/v1/admin/users"

        /** Never stored as a member: the administrator only needs a signed token. */
        const val ADMIN = 9_986_900L
        const val NOTE_BODY = "F31 owned synthetic note"
        const val CHAT_BODY = "F31 owned synthetic chat"

        val COMMUNITY_TABLES = arrayOf(
            "pawbridge_community.private_note_members",
            "pawbridge_community.private_notes",
            "pawbridge_community.private_note_mailboxes",
            "pawbridge_community.private_note_requests",
            "pawbridge_community.private_note_blocks",
            "pawbridge_community.member_chat_rooms",
            "pawbridge_community.member_chat_room_members",
            "pawbridge_community.member_chat_messages",
            "pawbridge_community.member_chat_requests",
        )

        /** The pending marker is added and removed again, so `contact_deletions` is unchanged in the end. */
        val DELETION_TABLES = COMMUNITY_TABLES + arrayOf(
            "pawbridge_animal.chatbot_sessions",
            "pawbridge_user.users",
            "pawbridge_user.favorites",
            "pawbridge_user.shelter_applications",
            "pawbridge_user.auth_sessions",
        )
    }
}
