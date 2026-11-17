"""Isolated DEV-041 checkpoint store. Only the synthetic verifier imports this module."""
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import sqlite3

SCHEMA = (
    """CREATE TABLE document (
           id TEXT PRIMARY KEY NOT NULL,
           revision INTEGER NOT NULL CHECK(revision >= 0),
           checkpoint BLOB NOT NULL
       )""",
    """CREATE TABLE legacy_source (
           document_id TEXT PRIMARY KEY NOT NULL REFERENCES document(id),
           original BLOB NOT NULL
       )""",
    """CREATE TABLE operation (
           id TEXT PRIMARY KEY NOT NULL,
           document_id TEXT NOT NULL REFERENCES document(id),
           base_revision INTEGER,
           revision INTEGER NOT NULL,
           checkpoint_sha256 BLOB NOT NULL,
           source_sha256 BLOB,
           UNIQUE(document_id, revision)
       )""",
)


@dataclass(frozen=True)
class Checkpoint:
    document_id: str
    revision: int
    canonical: bytes


@dataclass(frozen=True)
class Receipt:
    status: str
    revision: int


class Rejected(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).digest()


@contextmanager
def immediate(db):
    db.execute('BEGIN IMMEDIATE')
    try:
        yield
        db.execute('COMMIT')
    except BaseException:
        # SQLITE_FULL and some fatal write errors can roll back the transaction themselves.
        if db.in_transaction:
            db.execute('ROLLBACK')
        raise


@contextmanager
def open_store(folder, *, initialize=False):
    path = folder / 'checkpoint.sqlite'
    mode = 'rwc' if initialize else 'rw'
    db = sqlite3.connect(f'{path.as_uri()}?mode={mode}', timeout=5, isolation_level=None)
    try:
        db.execute('PRAGMA foreign_keys=ON')
        version, = db.execute('PRAGMA user_version').fetchone()
        if version == 0 and initialize:
            with immediate(db):
                for statement in SCHEMA:
                    db.execute(statement)
                db.execute('PRAGMA user_version=1')
        elif version != 1:
            raise Rejected('UNSUPPORTED_SCHEMA')
        if db.execute('PRAGMA journal_mode=WAL').fetchone() != ('wal',):
            raise Rejected('WAL_REQUIRED')
        db.execute('PRAGMA synchronous=FULL')
        if db.execute('PRAGMA synchronous').fetchone() != (2,):
            raise Rejected('FULL_REQUIRED')
        yield db
    finally:
        db.close()


def commit(db, operation_id, base_revision, checkpoint, *, original=None, phase=lambda _phase: None):
    """Publish checkpoint, exact operation identity and optional old bytes in one transaction."""
    if not operation_id or not checkpoint.document_id or checkpoint.revision < 0:
        raise Rejected('INVALID_IDENTITY')
    if original is not None and base_revision is not None:
        raise Rejected('IMPORT_REQUIRES_NEW_DOCUMENT')
    if original is None and checkpoint.revision != (0 if base_revision is None else base_revision + 1):
        raise Rejected('INVALID_REVISION')

    checkpoint_sha = digest(checkpoint.canonical)
    source_sha = digest(original) if original is not None else None
    identity = (checkpoint.document_id, base_revision, checkpoint.revision, checkpoint_sha, source_sha)
    with immediate(db):
        prior = db.execute(
            'SELECT document_id,base_revision,revision,checkpoint_sha256,source_sha256 FROM operation WHERE id=?',
            (operation_id,),
        ).fetchone()
        if prior is not None:
            if prior != identity:
                raise Rejected('OPERATION_CONFLICT')
            head = db.execute(
                """SELECT document.revision,checkpoint,checkpoint_sha256
                   FROM document LEFT JOIN operation
                   ON operation.document_id=document.id AND operation.revision=document.revision
                   WHERE document.id=?""",
                (checkpoint.document_id,),
            ).fetchone()
            if head is None or head[0] < checkpoint.revision or digest(head[1]) != head[2]:
                raise Rejected('REPAIR_REQUIRED')
            if original is not None:
                archived = db.execute('SELECT original FROM legacy_source WHERE document_id=?',
                                      (checkpoint.document_id,)).fetchone()
                if archived is None or digest(archived[0]) != source_sha:
                    raise Rejected('REPAIR_REQUIRED')
            return Receipt('REPLAYED', checkpoint.revision)

        head = db.execute('SELECT revision FROM document WHERE id=?', (checkpoint.document_id,)).fetchone()
        if head != (None if base_revision is None else (base_revision,)):
            raise Rejected('STALE_BASE')

        if base_revision is None:
            db.execute('INSERT INTO document VALUES(?,?,?)',
                       (checkpoint.document_id, checkpoint.revision, checkpoint.canonical))
        else:
            db.execute('UPDATE document SET revision=?,checkpoint=? WHERE id=?',
                       (checkpoint.revision, checkpoint.canonical, checkpoint.document_id))
        if original is not None:
            db.execute('INSERT INTO legacy_source VALUES(?,?)', (checkpoint.document_id, original))
        db.execute('INSERT INTO operation VALUES(?,?,?,?,?,?)', (operation_id, *identity))
        phase('before-commit')
    phase('after-commit')
    return Receipt('COMMITTED', checkpoint.revision)
