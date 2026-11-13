"""주 제공자의 회로 차단기. circuitbreaker 라이브러리를 쓴다. 예비 제공자가 있을 때만 쓴다(agent/service.py).

    닫힘 ─(못 쓰는 오류가 연달아 3번)→ 열림: 1분 동안 주 제공자를 부르지 않고 CircuitBreakerError를 낸다
    열림 ─(1분 지남)→ 반열림: 다음 질문을 주 제공자로 보낸다
    반열림 ─(성공)→ 닫힘,  반열림 ─(못 쓰는 오류)→ 다시 열림

"못 쓰는 오류"는 models.is_provider_unavailable(일시 장애, 크레딧 소진)다. 키 오류나 잘못된 요청,
우리 코드의 오류는 제공자가 응답한 것이므로 성공으로 센다(차단기가 열리지 않고 연속 횟수도 0이 된다).
uvicorn 프로세스 하나 안의 메모리 상태다.

라이브러리의 한계: 반열림 때 동시에 들어온 질문은 모두 주 제공자로 간다(시험 질문을 하나로 묶지 않는다).
지금 사용량에서는 동시 질문이 드물어 받아들인다.
"""

from circuitbreaker import CircuitBreaker, CircuitBreakerError

from app.chatbot.agent.models import is_provider_unavailable

# 연달아 3번. 각 실패는 SDK 재시도(1회)까지 실패한 것이라 순간 오류 한 번으로는 열리지 않는다.
FAILURE_THRESHOLD = 3
# 열린 뒤 주 제공자를 건너뛰는 시간. 지나면 다음 질문으로 회복을 시험한다.
OPEN_SECONDS = 60

__all__ = ["CircuitBreakerError", "PrimaryUnavailable", "guarded", "primary_breaker"]


class PrimaryUnavailable(Exception):
    """주 제공자를 못 쓴 질문. 차단기가 이 예외만 실패로 센다. 시도 결과(run, error)를 그대로 싣는다."""

    def __init__(self, run, error: Exception):
        super().__init__(str(error))
        self.run = run
        self.error = error


_PRIMARY = CircuitBreaker(
    failure_threshold=FAILURE_THRESHOLD,
    recovery_timeout=OPEN_SECONDS,
    expected_exception=PrimaryUnavailable,
    name="chatbot-primary-provider",
)


def primary_breaker() -> CircuitBreaker:
    return _PRIMARY


def guarded(run_once):
    """주 제공자 시도를 차단기로 감싼다. 열려 있으면 부르지 않고 CircuitBreakerError를 낸다.

    run_once는 오류를 예외 대신 (run, error)로 돌려주므로, 못 쓰는 오류만 PrimaryUnavailable로 바꿔
    차단기가 세게 한다. 데코레이터로 감싼다(라이브러리의 with 문 사용은 열림을 막지 않는다).
    """

    @_PRIMARY
    async def attempt(prompt, context):
        run, error = await run_once(prompt, context)
        if error is not None and is_provider_unavailable(error):
            raise PrimaryUnavailable(run, error)
        return run, error

    return attempt
