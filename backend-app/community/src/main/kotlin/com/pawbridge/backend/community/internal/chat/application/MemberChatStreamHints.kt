package com.pawbridge.backend.community.internal.chat.application

/** 웹소켓을 열지 않은 탭에 같은 신호를 알린다. 이 앱이 가진 연결에만 보내고, 본문은 싣지 않는다. */
fun interface MemberChatStreamHints {
    fun deliver(signal: MemberChatModels.Signal)
}
