from app.chatbot.health.answer import EVIDENCE_USE_RULES, HEALTH_RULES, NO_EVIDENCE_RULES, format_evidence

UNKNOWN_VALUES = {"", "UNKNOWN", "NONE", "NULL", "N/A", "미상", "모름", "알수없음", "알 수 없음"}

SPECIES_LABELS = {
    "DOG": "강아지",
    "CAT": "고양이",
}

GENDER_LABELS = {
    "MALE": "수컷",
    "FEMALE": "암컷",
}

NEUTERED_LABELS = {
    "YES": "중성화 완료",
    "NO": "중성화 미완료",
}

PROCESS_STATE_LABELS = {
    "PROTECT": "보호 중",
    "ADOPTED": "입양 완료",
    "RETURN": "반환",
    "EUTHANASIA": "안락사",
}


def _clean_value(value) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.upper() in UNKNOWN_VALUES:
        return None
    return text


def _label(mapping: dict[str, str], value: str | None) -> str | None:
    cleaned = _clean_value(value)
    if cleaned is None:
        return None
    return mapping.get(cleaned.upper(), cleaned)


def _format_animal_summary(animal_data: dict) -> str:
    species = _label(SPECIES_LABELS, animal_data.get("species"))
    breed = _clean_value(animal_data.get("breed"))
    age = _clean_value(animal_data.get("age"))
    weight = _clean_value(animal_data.get("weight"))
    color = _clean_value(animal_data.get("color"))
    gender = _label(GENDER_LABELS, animal_data.get("gender"))
    neutered = _label(NEUTERED_LABELS, animal_data.get("neutered"))
    special_mark = _clean_value(animal_data.get("specialMark"))
    process_state = _label(PROCESS_STATE_LABELS, animal_data.get("processState"))

    identity_parts = [part for part in [age, breed or species] if part]
    summary_parts = []
    if identity_parts:
        summary_parts.append(f"이 보호동물은 {' '.join(identity_parts)}입니다.")
    elif species:
        summary_parts.append(f"이 보호동물은 {species}입니다.")

    details = []
    if weight:
        details.append(f"체중은 {weight}")
    if color:
        details.append(f"색상은 {color}")
    if gender:
        details.append(f"성별은 {gender}")
    if neutered:
        details.append(f"중성화 정보는 {neutered}")
    if details:
        summary_parts.append(", ".join(details) + "입니다.")

    if special_mark:
        summary_parts.append(f"특징은 {special_mark}입니다.")
    if process_state:
        summary_parts.append(f"현재 공고 상태는 {process_state}입니다.")

    return " ".join(summary_parts) if summary_parts else "제공된 보호동물 상세 정보가 없습니다."


def _neutralize_brackets(value):
    if isinstance(value, str):
        return value.replace("[", "(").replace("]", ")")
    return value


def build_prompt(animal_context, recent_messages, question: str, health_evidence=None) -> str:
    """챗봇 프롬프트.

    health_evidence가 None이면 건강 해설 RAG를 쓰지 않는 기존 동작이다.
    목록이 있으면 근거 규칙과 근거를 더하고, 빈 목록(찾았지만 근거 없음)이면 의학 정보를 삼가는 규칙을 더한다.
    """
    animal_data = animal_context.model_dump()
    if health_evidence is not None:
        # 공고(보호소 입력)와 질문에 "[1]"이나 "[검수된 건강 근거]"를 넣어 가짜 근거처럼 보이게 하는 것을 막는다.
        animal_data = {key: _neutralize_brackets(value) for key, value in animal_data.items()}
        question = _neutralize_brackets(question)

    sections = _intro_sections(animal_data)
    if health_evidence:
        sections.extend(["\n".join(HEALTH_RULES), "[검수된 건강 근거]", format_evidence(health_evidence)])
    elif health_evidence is not None:
        sections.append("\n".join(NO_EVIDENCE_RULES))
    sections.extend(_conversation_sections(recent_messages, question))
    return "\n\n".join(sections)


# 보호소가 자유롭게 쓴 칸(입양 절차, 운영시간)이 길어도 프롬프트가 한없이 늘지 않게 자른다.
MAX_SHELTER_FIELD_CHARS = 400

SHELTER_LABELS = {
    "shelterName": "보호소 이름",
    "shelterPhone": "전화번호",
    "shelterAddress": "주소",
    "noticeEndDate": "공고 종료일",
    "operatingHours": "운영시간",
    "adoptionProcedure": "보호소가 적은 입양 절차",
}

AGENT_TOOL_RULES = [
    "search_health 도구는 출처가 확인된 건강 해설을 병명이나 증상 낱말로 찾습니다.",
    "질문이 병, 증상, 검사, 접종, 건강 관리에 관한데 아래 [검수된 건강 근거]가 없거나 질문에 맞지 않으면, 답을 쓰기 전에 반드시 이 도구를 먼저 부르세요.",
    "도구를 부르지 않고 '보호소나 동물병원에 문의하세요'로만 답하지 마세요. 그 안내는 도구로도 근거를 찾지 못했을 때만 씁니다.",
    "검색어는 질문 속 표현을 병명이나 증상 낱말로 바꿔 짧게 쓰세요. 예: '콜록거려요'는 '기침'.",
    "근거가 이미 질문에 맞거나 건강과 관계없는 질문이면 도구를 부르지 말고 바로 답하세요.",
    "질문이 모호해 답이 크게 달라질 때만 한 문장으로 되물으세요.",
    "adoption_guide 도구는 보호소 입양 자격, 공고 기간, 입양비 지원, 입양자 준수사항, 동물등록, 예비입양제의 공식 안내를 주제 이름으로 가져옵니다. 이런 질문에는 이 도구를 부르세요.",
    "find_similar_animals 도구는 지금 공고 동물과 사진 생김새가 비슷한, 공고중이거나 보호중인 다른 동물을 찾습니다.",
    "비슷한 아이, 닮은 아이, 다른 아이를 묻는 질문에는 답을 쓰기 전에 이 도구를 부르세요.",
    "이 도구가 찾은 동물 목록은 코드가 답 끝에 붙입니다. 답에 목록을 다시 쓰지 말고 안내 한두 문장만 쓰세요. 결과에 없는 동물, 이름, 성격, 건강 상태를 지어내지 마세요.",
    "도구 결과가 없거나 불러오지 못했다고 나오면 그대로 알리고 다른 동물을 소개하지 마세요.",
]

AGENT_EVIDENCE_RULES = [
    "[검수된 건강 근거]와 search_health 결과는 출처가 확인된 일반 정보입니다.",
    *EVIDENCE_USE_RULES,
    "도구로도 근거를 찾지 못한 건강 질문에는 병명, 증상의 원인, 치료 방법, 약, 수치, 회복 가능성을 말하지 말고, 공고에 적힌 내용과 보호소에 물어볼 것, 동물병원 진료가 필요하다는 점을 안내하세요.",
    "입양 자격, 절차, 비용, 지원금, 동물등록 같은 사실은 adoption_guide 결과에 있는 것만 말하세요. 금액과 기한은 결과에 적힌 그대로 쓰세요.",
    "건강과 입양 안내 모두와 관계없는 질문(성격, 준비물 등)은 평소처럼 답하세요.",
]

SHELTER_RULES = [
    "보호소 이름, 전화번호, 주소, 운영시간, 입양 절차는 [보호소 정보]에 적힌 그대로만 쓰세요.",
    "[보호소 정보]에 없는 내용은 지어내지 말고 보호소에 문의하라고 안내하세요.",
]

AGENT_ANSWER_FORMAT = (
    '최종 답은 다음 JSON 하나로만 하세요: {"answer": "답변", "used": [쓴 근거 번호]}. '
    "되묻거나 근거를 쓰지 않은 답이면 used는 빈 목록으로 두세요."
)


def build_agent_prompt(animal_context, recent_messages, question: str, health_evidence, shelter_context=None) -> str:
    """입양 상담 에이전트의 첫 프롬프트. 공고에서 찾은 근거는 처음부터 넣고, 더 필요하면 모델이 도구로 찾는다."""
    animal_data = {key: _neutralize_brackets(value) for key, value in animal_context.model_dump().items()}
    question = _neutralize_brackets(question)

    sections = _intro_sections(animal_data)
    sections.extend(["[보호소 정보]", _format_shelter(shelter_context), "\n".join(SHELTER_RULES)])
    sections.extend(["[도구 사용]", "\n".join(AGENT_TOOL_RULES), "\n".join(AGENT_EVIDENCE_RULES)])
    sections.append("[검수된 건강 근거]")
    sections.append(format_evidence(health_evidence) if health_evidence else "공고와 질문에서 바로 찾은 건강 근거는 없습니다.")
    sections.extend(_conversation_sections(recent_messages, question))
    sections.append(AGENT_ANSWER_FORMAT)
    return "\n\n".join(sections)


def _format_shelter(shelter_context) -> str:
    if shelter_context is None:
        return "제공된 보호소 정보가 없습니다."
    lines = []
    for key, label in SHELTER_LABELS.items():
        value = _clean_value(getattr(shelter_context, key))
        if value is None:
            continue
        if len(value) > MAX_SHELTER_FIELD_CHARS:
            value = value[:MAX_SHELTER_FIELD_CHARS].rstrip() + "…"
        lines.append(f"{label}: {_neutralize_brackets(value)}")
    return "\n".join(lines) if lines else "제공된 보호소 정보가 없습니다."


def _intro_sections(animal_data: dict) -> list[str]:
    """역할, 답변 가이드, 공고 정보. 고정 흐름과 에이전트가 같이 쓴다."""
    context_lines = [
        f"{key}: {value}"
        for key, value in animal_data.items()
        if _clean_value(value) is not None
    ]
    return [
        "당신은 PawBridge의 AI 입양 준비 도우미입니다.",
        "반드시 한국어로 답변하세요.",
        "인사말이나 서비스 이름으로 답변을 시작하지 마세요.",
        "보호동물 공고 정보는 참고 자료로만 사용하세요.",
        "입양 가능 여부를 판단하거나 보호소를 대신해 확정적으로 말하지 마세요.",
        "의학적 진단이나 치료 판단을 제공하지 마세요.",
        "답변은 실용적이고, 사용자의 실제 질문에 대한 답을 먼저 제시하세요.",
        (
            "safetyNotice는 서버가 별도로 붙입니다. 보호소 문의, 수의사 상담 같은 "
            "일반적인 안전 고지 문구를 반복하지 마세요. 다만 사용자의 질문이 알 수 없는 사실, "
            "의학적 판단, 입양 절차, 최종 확인을 요구할 때만 짧게 확인 필요성을 언급할 수 있습니다."
        ),
        (
            "질문 유형별 답변 가이드:\n"
            "- 돌봄 방법/생활 질문: 생활환경, 적응 기간, 식사, 배변, 산책/놀이, 관찰 신호를 중심으로 답하세요.\n"
            "- 입양 전 확인 질문: 보호소에 확인할 질문 목록을 구체적으로 제안하되, 건강, 성격, 사회성, 기존 생활 습관, 산책/목줄 적응, 중성화 여부를 포함하세요.\n"
            "- 건강 질문: 진단하지 말고 공고 정보에서 확인 가능한 내용과 관찰할 신호, 병원 확인이 필요한 상황을 구분하세요.\n"
            "- 비용/준비물 질문: 초기 준비물과 반복 관리 항목을 우선순위로 정리하세요.\n"
            "- 성격/적응 질문: 새 환경 적응, 가족 구성원, 기존 반려동물, 분리불안 관찰을 중심으로 답하세요."
        ),
        (
            "너무 긴 한 문단으로 쓰지 말고, 답변이 길어질 때만 의미 단위로 문단을 나누세요. "
            "문장마다 줄바꿈하지 마세요."
        ),
        "전체 답변은 실용적으로 3~5문장 정도로 간결하게 작성하세요.",
        "보호동물 공고 요약:",
        _format_animal_summary(animal_data),
        "보호동물 공고 원문 정보:",
        "\n".join(context_lines) if context_lines else "제공된 보호동물 공고 원문 정보가 없습니다.",
    ]


def _conversation_sections(recent_messages, question: str) -> list[str]:
    message_lines = [
        f"{message.role}: {message.content}"
        for message in recent_messages
    ]
    sections = []
    if message_lines:
        sections.extend(["최근 대화:", "\n".join(message_lines)])
    sections.extend(["사용자 질문:", question.strip()])
    return sections
