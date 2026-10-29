"""Isolated metadata query candidate. Never open an App database or freeze production DDL."""
from dataclasses import dataclass
import sqlite3

SORT_COLUMNS = {'NAME': 'name_key', 'UPDATED': 'updated_key', 'RECENT': 'recent_key'}
MAX_TIME = (1 << 63) - 1
SCHEMA = '''
CREATE TABLE fixture_scope(marker TEXT NOT NULL);
INSERT INTO fixture_scope VALUES('notecore-library-paging-1');
CREATE TABLE space_revision(space TEXT PRIMARY KEY, revision INTEGER NOT NULL
 CHECK(typeof(revision)='integer' AND revision>=0));
CREATE TABLE note_meta(
 space TEXT NOT NULL REFERENCES space_revision(space), note_id TEXT NOT NULL,
 title TEXT NOT NULL, updated INTEGER NOT NULL CHECK(updated>=0), opened INTEGER,
 favorite INTEGER NOT NULL CHECK(favorite IN (0,1)), trashed INTEGER NOT NULL CHECK(trashed IN (0,1)),
 name_key BLOB NOT NULL, updated_key BLOB NOT NULL, recent_key BLOB, id_key BLOB NOT NULL,
 PRIMARY KEY(space,note_id), CHECK(opened IS NULL OR opened>=0),
 CHECK((opened IS NULL)=(recent_key IS NULL)));
CREATE TRIGGER inserted AFTER INSERT ON note_meta BEGIN
 UPDATE space_revision SET revision=revision+1 WHERE space=NEW.space; END;
CREATE TRIGGER deleted AFTER DELETE ON note_meta BEGIN
 UPDATE space_revision SET revision=revision+1 WHERE space=OLD.space; END;
CREATE TRIGGER updated AFTER UPDATE ON note_meta BEGIN
 UPDATE space_revision SET revision=revision+1 WHERE space=OLD.space;
 UPDATE space_revision SET revision=revision+1 WHERE space=NEW.space AND NEW.space!=OLD.space; END;
CREATE TABLE document_body(note_id TEXT PRIMARY KEY, payload BLOB NOT NULL);
CREATE TABLE image_resource(hash TEXT PRIMARY KEY, payload BLOB NOT NULL);
'''


def text_key(value):
    # Kotlin String.compareTo compares UTF-16 units; SQLite BINARY text compares UTF-8 bytes.
    return value.encode('utf-16-be')


def time_key(value):
    if value is None:
        return None
    if type(value) is not int or not 0 <= value <= MAX_TIME:
        raise ValueError('INVALID_TIME')
    return (MAX_TIME - value).to_bytes(8, 'big')


def metadata_row(space, note_id, title, updated, opened, favorite, trashed):
    if not space or not note_id or len(text_key(note_id)) > 256 or len(text_key(title)) > 240:
        raise ValueError('INVALID_METADATA')
    return (space, note_id, title, updated, opened, int(favorite), int(trashed),
            text_key(title), time_key(updated), time_key(opened), text_key(note_id))


def initialize(db):
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('PRAGMA journal_mode=WAL')
    db.executescript(SCHEMA)
    # Fixed identifiers only. Payload tables and all full document data stay outside these indexes.
    for sort, column in SORT_COLUMNS.items():
        for favorite in (False, True):
            prefix = 'space,trashed,' + ('favorite,' if favorite else '')
            tail = ',note_id,title,updated,opened' + ('' if favorite else ',favorite')
            db.execute(f'CREATE INDEX page_{sort.lower()}_{int(favorite)} ON note_meta('
                       + prefix + column + ',id_key' + tail + ')')
    db.commit()


@dataclass(frozen=True)
class Query:
    space: str
    sort: str
    favorites_only: bool = False


@dataclass(frozen=True)
class Cursor:
    query: Query
    revision: int
    note_id: str
    order_key: bytes
    id_key: bytes


@dataclass(frozen=True)
class Page:
    rows: tuple
    revision: int
    next_cursor: Cursor | None
    query_plan: tuple


def read_page(db, query, limit=40, cursor=None, after_version_read=None):
    """One short read transaction. Any intervening metadata mutation requires a fresh traversal.

    after_version_read is an isolated conformance seam, never an App callback or second owner.
    The cursor is an in-process immutable value, not an untrusted wire token or authorization.
    """
    if (not isinstance(query, Query) or not isinstance(query.space, str) or not query.space
            or query.sort not in SORT_COLUMNS or type(query.favorites_only) is not bool
            or type(limit) is not int or not 1 <= limit <= 200):
        raise ValueError('INVALID_QUERY')
    if cursor is not None and (not isinstance(cursor, Cursor) or cursor.query != query):
        raise ValueError('WRONG_QUERY_CURSOR')
    if db.in_transaction:
        raise ValueError('CALLER_TRANSACTION_ACTIVE')
    column = SORT_COLUMNS[query.sort]
    predicates = 'space=? AND trashed=0'
    values = [query.space]
    if query.favorites_only:
        predicates += ' AND favorite=1'
    if query.sort == 'RECENT':
        predicates += ' AND recent_key IS NOT NULL'
    db.execute('BEGIN')
    try:
        marker = db.execute('SELECT marker FROM fixture_scope').fetchall()
        if marker != [('notecore-library-paging-1',)]:
            raise ValueError('NOT_FIXTURE_DATABASE')
        row = db.execute('SELECT revision FROM space_revision WHERE space=?', (query.space,)).fetchone()
        if row is None:
            raise ValueError('WRONG_SPACE')
        revision = row[0]
        if cursor is not None:
            if cursor.revision != revision:
                raise ValueError('STALE_REVISION')
            anchor = db.execute(f'SELECT {column},id_key FROM note_meta WHERE '
                                + predicates + ' AND note_id=?', (*values, cursor.note_id)).fetchone()
            if anchor != (cursor.order_key, cursor.id_key):
                raise ValueError('INVALID_CURSOR')
        if after_version_read is not None:
            after_version_read()
        if cursor is not None:
            predicates += f' AND ({column},id_key)>(?,?)'
            values += [cursor.order_key, cursor.id_key]
        sql = ('SELECT note_id,title,updated,opened,favorite,' + column + ',id_key FROM note_meta WHERE '
               + predicates + f' ORDER BY {column},id_key LIMIT ?')
        values.append(limit + 1)
        plan = tuple(row[3] for row in db.execute('EXPLAIN QUERY PLAN ' + sql, values))
        # fetchall is bounded by SQL LIMIT, not a whole-catalog enumeration followed by slicing.
        result = db.execute(sql, values).fetchall()
        rows = result[:limit]
        next_cursor = None
        if len(result) > limit:
            last = rows[-1]
            next_cursor = Cursor(query, revision, last[0], last[-2], last[-1])
        return Page(tuple(tuple(row[:5]) for row in rows), revision, next_cursor, plan)
    finally:
        db.rollback()  # Read snapshot ends here; no long-lived pagination transaction.
