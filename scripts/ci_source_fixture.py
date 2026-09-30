#!/usr/bin/env python3
"""Seed only a disposable TEST emulator with reserved, non-live source URLs."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess

PACKAGE = "com.shaterguy.fc2weeklyranker.dev"
PREFERENCE_PATH = "files/datastore/ranker_settings.preferences_pb"
FIXTURE_ORIGIN = "https://fixture.invalid"
SEED_VALUES = {"base_url": FIXTURE_ORIGIN, "jav_base_url": FIXTURE_ORIGIN, "content_mode": "FC2"}
REPO_ROOT = Path(__file__).resolve().parents[1]
FIXTURE_FILE = REPO_ROOT / "app/src/test/resources/ci-source-settings.preferences_pb"


def varint(value: int) -> bytes:
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def field(number: int, data: bytes) -> bytes:
    return varint((number << 3) | 2) + varint(len(data)) + data


def seed_bytes() -> bytes:
    # Pinned DataStore preferences.proto: map=1, entry key=1/value=2, Value.string=5.
    return b"".join(
        field(1, field(1, key.encode()) + field(2, field(5, value.encode())))
        for key, value in sorted(SEED_VALUES.items())
    )


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("invalid protobuf varint")


def fields(data: bytes):
    offset = 0
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            value, offset = read_varint(data, offset)
        elif wire in (1, 2, 5):
            if wire == 2:
                size, offset = read_varint(data, offset)
            else:
                size = 8 if wire == 1 else 4
            end = offset + size
            if end > len(data):
                raise ValueError("truncated protobuf field")
            value, offset = data[offset:end], end
        else:
            raise ValueError("unsupported protobuf wire type")
        yield number, wire, value


def string_preferences(data: bytes) -> dict[str, str | None]:
    result: dict[str, str | None] = {}
    for number, wire, entry in fields(data):
        if number != 1 or wire != 2:
            continue
        key, value = None, None
        for part, part_wire, raw in fields(entry):
            if part == 1 and part_wire == 2:
                key = raw.decode("utf-8")
            elif part == 2 and part_wire == 2:
                value = None
                for kind, kind_wire, encoded in fields(raw):
                    if kind == 5 and kind_wire == 2:
                        value = encoded.decode("utf-8")
        if key is not None:
            result[key] = value
    return result


def verify_fixture_values(data: bytes) -> None:
    if not data or len(data) > 1024 * 1024:
        raise RuntimeError("unexpected fixture preference size")
    values = string_preferences(data)
    for key in ("base_url", "jav_base_url"):
        if values.get(key) != FIXTURE_ORIGIN:
            raise RuntimeError(f"{key} is not the reserved CI fixture origin")
    if values.get("content_mode") not in ("FC2", "JAV"):
        raise RuntimeError("missing supported fixture content mode")


def emulator_command() -> list[str]:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise RuntimeError("emulator seeding is restricted to GitHub CI")
    serial = subprocess.check_output(["adb", "get-serialno"], text=True).strip()
    if not serial.startswith("emulator-"):
        raise RuntimeError("fixture seeding requires the disposable CI emulator")
    command = ["adb", "-s", serial]
    qemu = subprocess.check_output(command + ["shell", "getprop", "ro.kernel.qemu"], text=True).strip()
    if qemu != "1":
        raise RuntimeError("fixture seeding is forbidden on a physical device")
    return command


def read_emulator_preferences(command: list[str]) -> bytes:
    return subprocess.check_output(command + ["exec-out", "run-as", PACKAGE, "cat", PREFERENCE_PATH])


def seed_emulator() -> None:
    command = emulator_command()
    running = subprocess.run(command + ["shell", "pidof", PACKAGE], capture_output=True, text=True)
    if running.returncode not in (0, 1):
        raise RuntimeError("could not verify application process state")
    if running.returncode == 0 or running.stdout.strip():
        raise RuntimeError("seed only before the first application launch")
    exists = subprocess.run(command + ["shell", "run-as", PACKAGE, "test", "-e", PREFERENCE_PATH])
    if exists.returncode == 0:
        raise RuntimeError("refusing to overwrite existing preferences or retention evidence")
    if exists.returncode != 1:
        raise RuntimeError("could not verify fresh preference state")
    subprocess.run(command + ["shell", "run-as", PACKAGE, "mkdir", "-p", "files/datastore"], check=True)
    data = seed_bytes()
    subprocess.run(command + ["exec-in", "run-as", PACKAGE, "sh", "-c", "cat > " + PREFERENCE_PATH], input=data, check=True)
    actual = read_emulator_preferences(command)
    if actual != data:
        raise RuntimeError("fixture seed read-back mismatch")
    verify_fixture_values(actual)
    print("CI source preferences seeded and verified on disposable emulator")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("assert-emulator", "seed", "verify", "check-fixture", "verify-local"))
    parser.add_argument("path", nargs="?")
    args = parser.parse_args()
    if args.action == "assert-emulator":
        emulator_command()
        print("Disposable CI emulator identity verified before device changes")
    elif args.action == "seed":
        seed_emulator()
    elif args.action == "verify":
        verify_fixture_values(read_emulator_preferences(emulator_command()))
        print("CI source preferences still use reserved fixture origins")
    elif args.action == "check-fixture":
        actual = FIXTURE_FILE.read_bytes()
        if actual != seed_bytes():
            raise RuntimeError("checked-in seed differs from the encoder")
        verify_fixture_values(actual)
        print("Checked-in CI preference fixture matches the encoder")
    else:
        if not args.path:
            parser.error("verify-local requires a DataStore round-trip file")
        verify_fixture_values(Path(args.path).read_bytes())
        print("Pinned DataStore round-trip retained reserved fixture origins")


if __name__ == "__main__":
    main()
