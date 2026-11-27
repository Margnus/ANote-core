"""Isolated DEV-041 checkpoint store. Only the synthetic verifier imports this module."""
from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import json
import os
import sqlite3
import tempfile

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
    """CREATE TABLE resource (
           sha256 TEXT PRIMARY KEY NOT NULL,
           size INTEGER NOT NULL CHECK(size > 0)
       )""",
    """CREATE TABLE operation_resource (
           operation_id TEXT NOT NULL REFERENCES operation(id),
           sha256 TEXT NOT NULL REFERENCES resource(sha256),
           PRIMARY KEY(operation_id, sha256)
       )""",
    """CREATE TABLE import_task (
           operation_id TEXT PRIMARY KEY NOT NULL,
           document_id TEXT UNIQUE NOT NULL,
           identity TEXT NOT NULL
       )""",
    """CREATE TABLE import_task_resource (
           operation_id TEXT NOT NULL REFERENCES import_task(operation_id) ON DELETE CASCADE,
           sha256 TEXT NOT NULL,
           size INTEGER NOT NULL CHECK(size > 0),
           PRIMARY KEY(operation_id, sha256)
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
    db = sqlite3.connect(f'{path.as_uri()}?mode={mode}', timeout=5, isolation_level=None, uri=True)
    try:
        db.execute('PRAGMA foreign_keys=ON')
        version, = db.execute('PRAGMA user_version').fetchone()
        if version == 0 and initialize:
            with immediate(db):
                for statement in SCHEMA:
                    db.execute(statement)
                db.execute('PRAGMA user_version=2')
        elif version != 2:
            raise Rejected('UNSUPPORTED_SCHEMA')
        if db.execute('PRAGMA journal_mode=WAL').fetchone() != ('wal',):
            raise Rejected('WAL_REQUIRED')
        db.execute('PRAGMA synchronous=FULL')
        if db.execute('PRAGMA synchronous').fetchone() != (2,):
            raise Rejected('FULL_REQUIRED')
        yield db
    finally:
        db.close()


def matches_resource(target, sha, size):
    try:
        if target.is_symlink() or not target.is_file() or target.stat().st_size != size:
            return False
        with target.open('rb') as stream:
            return digest(stream.read(size + 1)).hex() == sha
    except FileNotFoundError:
        return False


def publish_resource(folder, sha, payload):
    """Validate a closed stage, then use the host fixture's atomic no-clobber link."""
    target = folder / 'resources' / sha
    if target.exists() or target.is_symlink():
        if matches_resource(target, sha, len(payload)):
            return
        raise Rejected('REPAIR_REQUIRED')
    target.parent.mkdir(exist_ok=True)
    staging = folder / 'staging'
    staging.mkdir(exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=staging, delete=False) as output:
        output.write(payload)
        output.flush()
        os.fsync(output.fileno())
        stage = staging / os.path.basename(output.name)
    if not matches_resource(stage, sha, len(payload)):
        raise Rejected('STAGING_FAILED')
    try:
        os.link(stage, target)
    except FileExistsError:
        pass  # Another writer can only have published through the same no-clobber boundary.
    if not matches_resource(target, sha, len(payload)):
        raise Rejected('REPAIR_REQUIRED')
    stage.unlink()


def read_head(db, document_id):
    return db.execute(
        """SELECT document.revision,checkpoint,checkpoint_sha256
           FROM document LEFT JOIN operation
           ON operation.document_id=document.id AND operation.revision=document.revision
           WHERE document.id=?""",
        (document_id,),
    ).fetchone()


def check_operation(db, operation_id, identity, manifest, folder, *, reserve=False):
    document_id, base_revision, revision, _, source_sha = identity
    prior = db.execute(
        'SELECT document_id,base_revision,revision,checkpoint_sha256,source_sha256 FROM operation WHERE id=?',
        (operation_id,),
    ).fetchone()
    if prior is not None:
        saved_manifest = db.execute(
            """SELECT resource.sha256,size FROM operation_resource JOIN resource USING (sha256)
               WHERE operation_id=? ORDER BY resource.sha256""",
            (operation_id,),
        ).fetchall()
        if prior != identity or saved_manifest != manifest:
            raise Rejected('OPERATION_CONFLICT')
        head = read_head(db, document_id)
        if head is None or head[0] < revision or digest(head[1]) != head[2]:
            raise Rejected('REPAIR_REQUIRED')
        if source_sha is not None:
            archived = db.execute('SELECT original FROM legacy_source WHERE document_id=?',
                                  (document_id,)).fetchone()
            if archived is None or digest(archived[0]) != source_sha:
                raise Rejected('REPAIR_REQUIRED')
        for sha, size in manifest:
            if not matches_resource(folder / 'resources' / sha, sha, size):
                raise Rejected('REPAIR_REQUIRED')
        return Receipt('REPLAYED', revision)

    task_identity = json.dumps([*identity[:3], identity[3].hex(), source_sha.hex() if source_sha else None])
    task = db.execute('SELECT identity FROM import_task WHERE operation_id=?', (operation_id,)).fetchone()
    if task is not None:
        pending_manifest = db.execute(
            'SELECT sha256,size FROM import_task_resource WHERE operation_id=? ORDER BY sha256',
            (operation_id,),
        ).fetchall()
        if task != (task_identity,) or pending_manifest != manifest:
            raise Rejected('OPERATION_CONFLICT')
    elif db.execute('SELECT 1 FROM import_task WHERE document_id=?', (document_id,)).fetchone():
        raise Rejected('DOCUMENT_RESERVED')
    head = read_head(db, document_id)
    if (head is None and base_revision is not None) or (head is not None and head[0] != base_revision):
        raise Rejected('STALE_BASE')
    if head is not None and digest(head[1]) != head[2]:
        raise Rejected('REPAIR_REQUIRED')
    if task is None and manifest:
        if not reserve:
            raise Rejected('REPAIR_REQUIRED')
        db.execute('INSERT INTO import_task VALUES(?,?,?)', (operation_id, document_id, task_identity))
        db.executemany('INSERT INTO import_task_resource VALUES(?,?,?)',
                       [(operation_id, *resource) for resource in manifest])
    return None


def commit(db, operation_id, base_revision, checkpoint, *, original=None, resources=(), folder=None,
           phase=lambda _phase: None):
    """Publish checkpoint, exact operation identity and optional archived source and attachments."""
    if not operation_id or not checkpoint.document_id or checkpoint.revision < 0:
        raise Rejected('INVALID_IDENTITY')
    if original is not None and base_revision is not None:
        raise Rejected('IMPORT_REQUIRES_NEW_DOCUMENT')
    if original is None and checkpoint.revision != (0 if base_revision is None else base_revision + 1):
        raise Rejected('INVALID_REVISION')
    if resources and (original is None or folder is None):
        raise Rejected('RESOURCES_REQUIRE_IMPORT')
    if any(not 0 < len(payload) <= 1024 * 1024 for payload in resources):
        raise Rejected('INVALID_RESOURCE_SIZE')

    checkpoint_sha = digest(checkpoint.canonical)
    source_sha = digest(original) if original is not None else None
    identity = (checkpoint.document_id, base_revision, checkpoint.revision, checkpoint_sha, source_sha)
    payloads = {digest(payload).hex(): payload for payload in resources}
    manifest = sorted((sha, len(payload)) for sha, payload in payloads.items())
    if manifest:
        with immediate(db):
            replay = check_operation(db, operation_id, identity, manifest, folder, reserve=True)
        if replay is not None:
            return replay
        for sha, _ in manifest:
            publish_resource(folder, sha, payloads[sha])
            phase('resource-published')

    with immediate(db):
        replay = check_operation(db, operation_id, identity, manifest, folder)
        if replay is not None:
            return replay
        if base_revision is None:
            db.execute('INSERT INTO document VALUES(?,?,?)',
                       (checkpoint.document_id, checkpoint.revision, checkpoint.canonical))
        else:
            db.execute('UPDATE document SET revision=?,checkpoint=? WHERE id=?',
                       (checkpoint.revision, checkpoint.canonical, checkpoint.document_id))
        if original is not None:
            db.execute('INSERT INTO legacy_source VALUES(?,?)', (checkpoint.document_id, original))
        db.execute('INSERT INTO operation VALUES(?,?,?,?,?,?)', (operation_id, *identity))
        for sha, size in manifest:
            if not matches_resource(folder / 'resources' / sha, sha, size):
                raise Rejected('REPAIR_REQUIRED')
            db.execute('INSERT INTO resource VALUES(?,?) ON CONFLICT(sha256) DO NOTHING', (sha, size))
            if db.execute('SELECT size FROM resource WHERE sha256=?', (sha,)).fetchone() != (size,):
                raise Rejected('REPAIR_REQUIRED')
            db.execute('INSERT INTO operation_resource VALUES(?,?)', (operation_id, sha))
        if manifest:
            db.execute('DELETE FROM import_task WHERE operation_id=?', (operation_id,))
        phase('before-commit')
    phase('after-commit')
    return Receipt('COMMITTED', checkpoint.revision)
