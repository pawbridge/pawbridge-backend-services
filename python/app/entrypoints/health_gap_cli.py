"""공고 특이사항에서 건강 해설 빈틈 후보를 뽑는다. 모델 호출, 네트워크, DB 접속이 없다.

python -m app.entrypoints.health_gap_cli --notices /path/to/notices.csv --output /path/to/gaps.md

입력은 미리 내보낸 CSV(머리글 id, species, status, special_mark)다. 결과는 사람이 보고 정하는 후보 목록이다.
"""

import argparse
import csv
import dataclasses
import json
import sys
from pathlib import Path

from app.chatbot.health.gaps import (
    DEFAULT_DECISIONS_PATH,
    Notice,
    find_gaps,
    load_decisions,
    render_markdown,
)
from app.chatbot.health.knowledge import DEFAULT_ENTRIES_DIR, load_entries

REQUIRED_COLUMNS = ("id", "species", "status", "special_mark")
# 특이사항 한 칸이 csv 기본 한도(131072자)를 넘어도 읽는다.
MAX_FIELD_CHARS = 10_000_000


def read_notices(path: Path, protected_status: str) -> list[Notice]:
    csv.field_size_limit(MAX_FIELD_CHARS)
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = [column for column in REQUIRED_COLUMNS if column not in (reader.fieldnames or [])]
        if missing:
            raise SystemExit(f"{path.name}: 머리글에 {', '.join(missing)} 칸이 없습니다")
        notices = []
        for row in reader:
            text = row["special_mark"] or ""
            if not text.strip():
                continue
            notices.append(
                Notice(
                    notice_id=row["id"],
                    species=row["species"] or None,
                    protected=row["status"] == protected_status,
                    text=text,
                )
            )
    return notices


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--notices", required=True, type=Path, help="공고 CSV (id, species, status, special_mark)")
    parser.add_argument("--decisions", type=Path, default=DEFAULT_DECISIONS_PATH, help="사람이 정한 목록 파일")
    parser.add_argument("--entries-dir", type=Path, default=DEFAULT_ENTRIES_DIR, help="해설 폴더")
    parser.add_argument("--protected-status", default="PROTECT", help="보호중을 뜻하는 status 값")
    parser.add_argument("--min-count", type=int, default=2, help="후보로 보여 줄 최소 공고 수")
    parser.add_argument("--frequent-words", type=int, default=100, help="나머지 낱말을 몇 개까지 보여 줄지")
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    parser.add_argument("--output", type=Path, help="결과 파일. 없으면 표준 출력")
    args = parser.parse_args()

    report = find_gaps(
        read_notices(args.notices, args.protected_status),
        load_entries(args.entries_dir),
        load_decisions(args.decisions),
        min_count=args.min_count,
        frequent_word_limit=args.frequent_words,
    )
    if args.format == "json":
        rendered = json.dumps(dataclasses.asdict(report), ensure_ascii=False, indent=2) + "\n"
    else:
        rendered = render_markdown(report)
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)


if __name__ == "__main__":
    main()
