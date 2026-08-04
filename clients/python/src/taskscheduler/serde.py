"""Payload serialisation: dataclass (or pydantic model) <-> ``payload_json``.

The scheduler stores two strings per job — ``payload_type`` (a stable name) and
``payload_json`` (the encoded arguments). This module owns the mapping between those two
strings and a Python object.

Compatibility rules mirror the Kotlin side (DESIGN.md 22.9) so a payload can evolve without
stranding jobs that are already in the queue:

* unknown keys in the stored JSON are **ignored** on decode — removing a field is safe;
* a field missing from the stored JSON falls back to its Python default — adding a field
  with a default is safe;
* a missing field with **no** default is a hard error (terminal FAILED, no retry), because
  the stored bytes will never change.
"""

from __future__ import annotations

import dataclasses
import json
import types
import typing
import uuid
from datetime import date, datetime, timezone
from decimal import Decimal
from enum import Enum
from typing import Any, TypeVar, cast

from .errors import PayloadDecodeError

__all__ = ["encode_payload", "decode_payload", "payload_type_of", "to_jsonable"]

T = TypeVar("T")

_NONE_TYPE = type(None)


def payload_type_of(cls: type) -> str:
    """The ``payload_type`` string for a payload class.

    Explicit ``__taskscheduler_type__`` (set by :func:`taskscheduler.job_type`) wins;
    otherwise the fully-qualified Python name is used, which keeps types unique across
    modules without any registration ceremony.
    """
    explicit = getattr(cls, "__taskscheduler_type__", None)
    if explicit:
        return str(explicit)
    return f"{cls.__module__}.{cls.__qualname__}"


def encode_payload(payload: Any) -> str:
    """Serialise a payload object to the JSON string stored in ``job.payload_json``."""
    return json.dumps(to_jsonable(payload), ensure_ascii=False, separators=(",", ":"))


def decode_payload(cls: type[T], payload_json: str | bytes | dict[str, Any]) -> T:
    """Rebuild a payload object of type ``cls`` from stored JSON.

    Accepts either the raw JSON text or an already-decoded mapping, since some drivers
    hand back JSONB columns pre-parsed.

    Raises :class:`PayloadDecodeError` on anything the class cannot accept — the caller
    turns that into a terminal FAILED without retrying.
    """
    if isinstance(payload_json, dict):
        raw: Any = payload_json
    else:
        try:
            raw = json.loads(payload_json)
        except (ValueError, TypeError) as exc:
            raise PayloadDecodeError(f"payload_json is not valid JSON: {exc}") from exc
    if not isinstance(raw, dict):
        raise PayloadDecodeError(
            f"payload_json for {payload_type_of(cls)} must be a JSON object, "
            f"got {type(raw).__name__}"
        )
    try:
        return cast(T, _from_jsonable(cls, raw))
    except PayloadDecodeError:
        raise
    except Exception as exc:
        raise PayloadDecodeError(
            f"cannot decode payload for {payload_type_of(cls)}: {exc}"
        ) from exc


# --------------------------------------------------------------------------------------
# encoding
# --------------------------------------------------------------------------------------


def to_jsonable(value: Any) -> Any:
    """Convert ``value`` into something :func:`json.dumps` accepts, recursively."""
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    # pydantic v2 models expose model_dump; duck-typed so pydantic stays an optional dep.
    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump) and not isinstance(value, type):
        return to_jsonable(model_dump(mode="python"))
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {f.name: to_jsonable(getattr(value, f.name)) for f in dataclasses.fields(value)}
    if isinstance(value, Enum):
        return to_jsonable(value.value)
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, datetime):
        return _encode_datetime(value)
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Decimal):
        # String, not float — a Decimal is used precisely when float rounding is unacceptable.
        return str(value)
    if isinstance(value, (bytes, bytearray)):
        import base64

        return base64.b64encode(bytes(value)).decode("ascii")
    if isinstance(value, dict):
        return {str(k): to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_jsonable(v) for v in value]
    raise TypeError(f"cannot serialise {type(value).__name__} into payload_json")


def _encode_datetime(value: datetime) -> str:
    """ISO-8601 in UTC with a ``Z`` suffix — the format the dashboard renders."""
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    text = value.astimezone(timezone.utc).isoformat()
    return text.replace("+00:00", "Z")


# --------------------------------------------------------------------------------------
# decoding
# --------------------------------------------------------------------------------------


def _from_jsonable(target: Any, raw: Any) -> Any:
    origin = typing.get_origin(target)

    if target is Any or target is None:
        return raw

    if origin is typing.Union or origin is types.UnionType:
        return _decode_union(target, raw)

    if origin in (list, set, frozenset, tuple):
        return _decode_sequence(target, origin, raw)

    if origin is dict:
        key_t, val_t = typing.get_args(target) or (str, Any)
        if not isinstance(raw, dict):
            raise PayloadDecodeError(
                f"expected a JSON object for {target}, got {type(raw).__name__}"
            )
        return {_from_jsonable(key_t, k): _from_jsonable(val_t, v) for k, v in raw.items()}

    if isinstance(target, type):
        return _decode_class(target, raw)

    return raw


def _decode_union(target: Any, raw: Any) -> Any:
    args = [a for a in typing.get_args(target) if a is not _NONE_TYPE]
    if raw is None:
        return None
    last: Exception | None = None
    for arg in args:
        try:
            return _from_jsonable(arg, raw)
        except Exception as exc:  # try the next member of the union
            last = exc
    raise PayloadDecodeError(f"value {raw!r} matches no member of {target}: {last}")


def _decode_sequence(target: Any, origin: Any, raw: Any) -> Any:
    if not isinstance(raw, list):
        raise PayloadDecodeError(f"expected a JSON array for {target}, got {type(raw).__name__}")
    args = typing.get_args(target)
    if origin is tuple and args and Ellipsis not in args:
        if len(args) != len(raw):
            raise PayloadDecodeError(f"expected {len(args)} elements for {target}, got {len(raw)}")
        return tuple(_from_jsonable(a, v) for a, v in zip(args, raw, strict=True))
    item_t = args[0] if args else Any
    decoded = [_from_jsonable(item_t, v) for v in raw]
    if origin is set:
        return set(decoded)
    if origin is frozenset:
        return frozenset(decoded)
    if origin is tuple:
        return tuple(decoded)
    return decoded


def _decode_class(target: type, raw: Any) -> Any:
    if target in (str, int, float, bool):
        # bool is a subclass of int — check it first so True doesn't silently become 1.
        if target is bool and not isinstance(raw, bool):
            raise PayloadDecodeError(f"expected a JSON boolean, got {type(raw).__name__}")
        if target is not bool and isinstance(raw, bool):
            raise PayloadDecodeError(f"expected {target.__name__}, got a JSON boolean")
        return target(raw)
    if issubclass(target, Enum):
        return target(raw)
    if target is uuid.UUID:
        return uuid.UUID(str(raw))
    if target is datetime:
        return _decode_datetime(str(raw))
    if target is date:
        return date.fromisoformat(str(raw))
    if target is Decimal:
        return Decimal(str(raw))
    if target in (bytes, bytearray):
        import base64

        return target(base64.b64decode(str(raw)))

    model_validate = getattr(target, "model_validate", None)
    if callable(model_validate):
        return model_validate(raw)

    if dataclasses.is_dataclass(target):
        return _decode_dataclass(target, raw)

    return raw


def _decode_dataclass(target: type, raw: Any) -> Any:
    if not isinstance(raw, dict):
        raise PayloadDecodeError(
            f"expected a JSON object for {target.__name__}, got {type(raw).__name__}"
        )
    hints = typing.get_type_hints(target)
    kwargs: dict[str, Any] = {}
    missing: list[str] = []
    for field in dataclasses.fields(target):
        if not field.init:
            continue
        if field.name in raw:
            kwargs[field.name] = _from_jsonable(hints.get(field.name, Any), raw[field.name])
        elif (
            field.default is dataclasses.MISSING
            and field.default_factory is dataclasses.MISSING
        ):
            missing.append(field.name)
        # else: leave it out so the dataclass default applies.
    if missing:
        raise PayloadDecodeError(
            f"payload_json for {payload_type_of(target)} is missing required field(s) "
            f"{', '.join(missing)} — the payload class changed incompatibly. "
            f"Give the field a default, or version the type (SendEmailV2)."
        )
    # Unknown keys in `raw` are deliberately ignored — see the module docstring.
    return target(**kwargs)


def _decode_datetime(text: str) -> datetime:
    normalised = text[:-1] + "+00:00" if text.endswith("Z") else text
    parsed = datetime.fromisoformat(normalised)
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
