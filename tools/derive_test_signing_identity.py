#!/usr/bin/env python3
"""Derive the stable FC2 Weekly Ranker TEST signing identity from a private passphrase."""

from __future__ import annotations

import argparse
import hashlib
import math
from datetime import datetime, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

E = 65537
BITS = 1024
ROUNDS = 32
DOMAIN = b"fc2-weekly-ranker-test-signing-v1|"


def probable_prime(n: int) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47):
        if n == p:
            return True
        if n % p == 0:
            return False
    d, s = n - 1, 0
    while d % 2 == 0:
        s += 1
        d //= 2
    raw_n = n.to_bytes((n.bit_length() + 7) // 8, "big")
    for i in range(ROUNDS):
        digest = hashlib.sha256(raw_n + i.to_bytes(4, "big")).digest()
        a = 2 + int.from_bytes(digest, "big") % (n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def derive_prime(secret: bytes, label: bytes) -> int:
    raw = hashlib.shake_256(DOMAIN + secret + b"|" + label).digest(BITS // 8)
    candidate = int.from_bytes(raw, "big") | (1 << (BITS - 1)) | 1
    candidate &= (1 << BITS) - 1
    candidate |= 1 << (BITS - 1)
    while True:
        if probable_prime(candidate):
            return candidate
        candidate += 2
        if candidate.bit_length() > BITS:
            candidate = (1 << (BITS - 1)) | 1


def derive_key(secret: bytes):
    p = derive_prime(secret, b"p")
    q = derive_prime(secret, b"q")
    if p == q:
        q = derive_prime(secret, b"q2")
    phi = (p - 1) * (q - 1)
    if math.gcd(E, phi) != 1:
        q = derive_prime(secret, b"q3")
        phi = (p - 1) * (q - 1)
    d = pow(E, -1, phi)
    return rsa.RSAPrivateNumbers(
        p=p,
        q=q,
        d=d,
        dmp1=d % (p - 1),
        dmq1=d % (q - 1),
        iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(E, p * q),
    ).private_key()


def derive_certificate(secret: bytes, key):
    subject = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, "FC2 Weekly Ranker Android Test"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "shaterguy"),
    ])
    serial = max(1, int.from_bytes(hashlib.sha256(b"fc2-weekly-ranker-test-cert-v1|" + secret).digest()[:19], "big"))
    return (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(serial)
        .not_valid_before(datetime(2026, 8, 30, tzinfo=timezone.utc))
        .not_valid_after(datetime(2056, 8, 30, tzinfo=timezone.utc))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pass-file", required=True)
    parser.add_argument("--out-key", required=True)
    parser.add_argument("--out-cert", required=True)
    args = parser.parse_args()
    secret = Path(args.pass_file).read_bytes().strip()
    if len(secret) < 32:
        raise SystemExit("signing passphrase is unexpectedly short")
    key = derive_key(secret)
    cert = derive_certificate(secret, key)
    key_path = Path(args.out_key)
    key_path.write_bytes(key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    key_path.chmod(0o600)
    Path(args.out_cert).write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(cert.fingerprint(hashes.SHA256()).hex())


if __name__ == "__main__":
    main()
