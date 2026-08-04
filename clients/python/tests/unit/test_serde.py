"""Payload encoding/decoding, including the schema-evolution rules."""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from decimal import Decimal
from enum import Enum

import pytest

from taskscheduler import PayloadDecodeError, job_type
from taskscheduler.serde import decode_payload, encode_payload, payload_type_of


class Channel(str, Enum):
    EMAIL = "EMAIL"
    SMS = "SMS"


@job_type
@dataclass
class Simple:
    user_id: int
    template: str


@dataclass
class Nested:
    label: str
    weight: float


@job_type("pinned.custom.Name")
@dataclass
class Pinned:
    value: int


@dataclass
class Rich:
    ident: uuid.UUID
    when: datetime
    amount: Decimal
    channel: Channel
    nested: Nested
    tags: list[str] = field(default_factory=list)
    lookup: dict[str, int] = field(default_factory=dict)
    optional: str | None = None


def test_payload_type_defaults_to_qualified_python_name():
    assert payload_type_of(Simple).endswith(".Simple")
    assert "test_serde" in payload_type_of(Simple)


def test_payload_type_can_be_pinned():
    assert payload_type_of(Pinned) == "pinned.custom.Name"


def test_simple_roundtrip():
    original = Simple(user_id=42, template="welcome")
    assert decode_payload(Simple, encode_payload(original)) == original


def test_rich_types_roundtrip():
    original = Rich(
        ident=uuid.uuid4(),
        when=datetime(2026, 8, 3, 12, 30, tzinfo=timezone.utc),
        amount=Decimal("19.99"),
        channel=Channel.SMS,
        nested=Nested(label="x", weight=1.5),
        tags=["a", "b"],
        lookup={"k": 1},
        optional=None,
    )
    assert decode_payload(Rich, encode_payload(original)) == original


def test_decimal_is_encoded_as_string_to_keep_precision():
    @dataclass
    class Money:
        amount: Decimal

    encoded = encode_payload(Money(Decimal("0.1")))
    assert '"0.1"' in encoded
    assert decode_payload(Money, encoded).amount == Decimal("0.1")


def test_naive_datetime_is_treated_as_utc():
    @dataclass
    class Timed:
        when: datetime

    encoded = encode_payload(Timed(datetime(2026, 8, 3, 12, 0)))
    assert encoded.endswith('Z"}')
    assert decode_payload(Timed, encoded).when.tzinfo is not None


# --- schema evolution ------------------------------------------------------------------


def test_removed_field_is_ignored_on_decode():
    """A field dropped from the class must not strand jobs already in the queue."""
    decoded = decode_payload(Simple, '{"user_id": 1, "template": "x", "removed_field": 99}')
    assert decoded == Simple(user_id=1, template="x")


def test_added_field_with_default_decodes_old_payloads():
    @dataclass
    class WithNewField:
        user_id: int
        template: str
        from_address: str = "noreply@example.com"

    decoded = decode_payload(WithNewField, '{"user_id": 1, "template": "x"}')
    assert decoded.from_address == "noreply@example.com"


def test_missing_required_field_is_a_decode_error():
    """No default means the job cannot run — and no retry will change that."""
    with pytest.raises(PayloadDecodeError, match="missing required field"):
        decode_payload(Simple, '{"user_id": 1}')


def test_malformed_json_is_a_decode_error():
    with pytest.raises(PayloadDecodeError, match="not valid JSON"):
        decode_payload(Simple, "{not json")


def test_non_object_json_is_a_decode_error():
    with pytest.raises(PayloadDecodeError, match="must be a JSON object"):
        decode_payload(Simple, "[1, 2, 3]")


def test_bool_is_not_silently_accepted_as_int():
    with pytest.raises(PayloadDecodeError):
        decode_payload(Simple, '{"user_id": true, "template": "x"}')


def test_unserialisable_value_is_rejected_eagerly():
    @dataclass
    class Bad:
        thing: object

    with pytest.raises(TypeError, match="cannot serialise"):
        encode_payload(Bad(thing=object()))
