"""Paging helpers for recommendation results."""


def page_slice(items: list, page: int, size: int) -> list:
    """Return the items on the given page. Pages are numbered from 1."""
    if page < 1 or size < 1:
        raise ValueError("page and size must be at least 1")
    start = page * size
    return items[start:start + size]


def total_pages(total: int, size: int) -> int:
    """Number of pages needed to hold `total` items."""
    return math.ceil(total / size)
