"""Text-source verification uses UTF-8/LF; artifacts and legacy evidence remain byte exact."""
import hashlib
from pathlib import Path

TEXT_SUFFIXES = {'.kt', '.kts', '.py', '.json', '.md', '.sh', '.properties', '.xml'}


def verification_bytes(path: Path) -> bytes:
    data = path.read_bytes()
    return data.replace(b'\r\n', b'\n') if path.suffix in TEXT_SUFFIXES else data


def verification_digest(path: Path) -> str:
    return hashlib.sha256(verification_bytes(path)).hexdigest()
