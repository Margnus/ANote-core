"""Export exact bounded candidate SQL/typed values for read-only native engine conformance."""
from pathlib import Path
import hashlib
import json
import sqlite3
import sys

from library_paging_probe import MAX_TIME, Query, initialize, metadata_row, read_page
from verify_library_paging import oracle

ROOT = Path(__file__).resolve().parents[1]


def digest_rows(rows):
    digest = hashlib.sha256()
    for row in rows:
        for value in row:
            tag = 'N' if value is None else 'B' if isinstance(value, bytes) else 'I' if type(value) is int else 'T'
            data = b'' if value is None else value if isinstance(value, bytes) else str(value).encode('utf-8')
            digest.update(tag.encode()+str(len(data)).encode()+b':'+data+b'\n')
    return digest.hexdigest()


class ReplayCursor:
    def __init__(self, rows):
        self.rows = rows
    def fetchall(self):
        return self.rows
    def fetchone(self):
        return self.rows[0] if self.rows else None
    def __iter__(self):
        return iter(self.rows)


class RecordingConnection(sqlite3.Connection):
    cases = None
    def execute(self, sql, parameters=()):
        cursor = super().execute(sql, parameters)
        if self.cases is None or not sql.startswith(('SELECT', 'EXPLAIN')):
            return cursor
        rows = cursor.fetchall()
        self.cases.append({'sql': sql,
            'parameters': [{'type': 'blob', 'hex': p.hex()} if isinstance(p, bytes)
                           else {'type': 'integer' if type(p) is int else 'text', 'value': p} for p in parameters],
            'kind': 'plan' if sql.startswith('EXPLAIN') else 'query',
            'rows': len(rows), 'sha256': digest_rows(rows)})
        return ReplayCursor(rows)


def main():
    folder = Path(sys.argv[1]).resolve()
    assert folder.is_relative_to(ROOT/'build') and not folder.exists()
    folder.mkdir(parents=True)
    titles = ['中文笔记', 'Alpha', 'alpha', '', '同名', '\U0001f600', '\ue000', 'e\u0301', '\u00e9']
    notes = [('primary', f'n-{i:05d}', titles[i % len(titles)],
              (0 if i % 31 == 0 else MAX_TIME if i % 31 == 1 else i % 97),
              None if i % 7 == 0 else (MAX_TIME if i % 29 == 0 else i % 61),
              i % 3 == 0, i % 11 == 0) for i in range(10_000)]
    notes += [('primary', name, '同名', 42, 42, True, False) for name in ('id-\U0001f600', 'id-\ue000')]
    notes += [('other', 'foreign', 'Alpha', MAX_TIME, MAX_TIME, True, False)]
    db = sqlite3.connect(folder/'fixture.sqlite', factory=RecordingConnection)
    try:
        initialize(db)
        db.executemany('INSERT INTO space_revision VALUES(?,0)', [('primary',), ('other',), ('empty',)])
        db.executemany('INSERT INTO note_meta VALUES(?,?,?,?,?,?,?,?,?,?,?)', [metadata_row(*r) for r in notes])
        db.execute('INSERT INTO document_body VALUES(?,?)', ('n-00000', b'UNREAD BODY'*1000))
        db.execute('INSERT INTO image_resource VALUES(?,?)', ('synthetic', b'UNREAD IMAGE'*1000))
        db.commit()
        baseline = json.loads((ROOT/'reports/library-paging.json').read_text(encoding='utf-8'))
        db.cases = []
        for sort in ('NAME', 'UPDATED', 'RECENT'):
            for favorite in (False, True):
                q = Query('primary', sort, favorite)
                actual, cursor = [], None
                while True:
                    page = read_page(db, q, 37, cursor)
                    actual.extend(page.rows)
                    cursor = page.next_cursor
                    if cursor is None:
                        break
                assert actual == oracle(notes, q)
                previous = next(r for r in baseline['queries'] if r['sort'] == sort and r['favorites_only'] == favorite)
                assert hashlib.sha256(json.dumps(actual, ensure_ascii=False).encode()).hexdigest() == previous['ordered_rows_sha256']
        cases = db.cases
        db.cases = None
        db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
        db.execute('PRAGMA journal_mode=DELETE')
    finally:
        db.close()
    (folder/'cases.json').write_text(json.dumps({'schema': 'library-sql-replay-1', 'cases': cases}, ensure_ascii=False,
                                              separators=(',', ':'))+'\n', encoding='utf-8', newline='\n')
    print(json.dumps({'cases': len(cases), 'queries': sum(c['kind'] == 'query' for c in cases),
                      'plans': sum(c['kind'] == 'plan' for c in cases),
                      'fixture_sha256': hashlib.sha256((folder/'fixture.sqlite').read_bytes()).hexdigest()}))


if __name__ == '__main__':
    main()
