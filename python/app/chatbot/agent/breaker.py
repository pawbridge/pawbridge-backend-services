"""주 제공자의 회로 차단기. 예비 제공자가 있을 때만 쓴다(agent/service.py).

    닫힘 ─(못 쓰는 오류가 1분 안에 3번)→ 열림: 1분 동안 주 제공자를 건너뛰고 바로 예비 제공자
    열림 ─(1분 지남)→ 시험: 다음 질문 하나만 주 제공자로 보낸다(그동안 다른 질문은 계속 예비)
    시험 ─(성공)→ 닫힘,  시험 ─(못 쓰는 오류)→ 다시 열림

"못 쓰는 오류"는 models.is_provider_unavailable(일시 장애, 크레딧 소진)다. 키 오류나 잘못된 요청,
우리 코드의 오류는 제공자가 응답한 것이므로 성공으로 센다(차단기가 열리지 않는다).
uvicorn 프로세스 하나 안의 메모리 상태다. 기다림(await)이 없는 메서드만 있어 잠금이 필요 없다.
"""

import time
from collections import deque
from collections.abc import Callable

# 1분 안에 3번. 각 실패는 SDK 재시도(1회)까지 실패한 것이라 순간 오류 한 번으로는 열리지 않는다.
FAILURE_THRESHOLD = 3
FAILURE_WINDOW_SECONDS = 60.0
# 열린 뒤 주 제공자를 건너뛰는 시간. 지나면 질문 하나로 회복을 시험한다.
OPEN_SECONDS = 60.0


class ProviderBreaker:
    def __init__(
        self,
        threshold: int = FAILURE_THRESHOLD,
        window_seconds: float = FAILURE_WINDOW_SECONDS,
        open_seconds: float = OPEN_SECONDS,
        clock: Callable[[], float] = time.monotonic,
    ):
        self.threshold = threshold
        self.window_seconds = window_seconds
        self.open_seconds = open_seconds
        self.clock = clock
        self.failures: deque[float] = deque()
        self.open_until: float | None = None
        self.probing = False

    @property
    def state(self) -> str:
        if self.open_until is None:
            return "closed"
        if self.probing:
            return "probing"
        return "open" if self.clock() < self.open_until else "half_open"

    def allow(self) -> bool:
        """이번 질문을 주 제공자로 보내도 되는가. 열림이 끝난 뒤 처음 묻는 질문이 시험을 맡는다."""
        state = self.state
        if state == "closed":
            return True
        if state == "half_open":
            self.probing = True
            return True
        return False

    def record(self, unavailable: bool) -> None:
        """주 제공자로 보낸 질문의 결과를 적는다. allow()가 참이었던 질문마다 한 번 부른다."""
        if not unavailable:
            self.failures.clear()
            self.open_until = None
            self.probing = False
            return
        now = self.clock()
        if self.probing:
            self.probing = False
            self.open_until = now + self.open_seconds
            return
        self.failures.append(now)
        while self.failures and now - self.failures[0] > self.window_seconds:
            self.failures.popleft()
        if len(self.failures) >= self.threshold:
            self.failures.clear()
            self.open_until = now + self.open_seconds

    def reset(self) -> None:
        self.record(unavailable=False)


_PRIMARY = ProviderBreaker()


def primary_breaker() -> ProviderBreaker:
    return _PRIMARY
