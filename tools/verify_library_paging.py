#!/usr/bin/env python3
"""Actual isolated host SQLite keyset/query conformance; no production or device claim."""
from dataclasses import replace
from pathlib import Path
import hashlib
import json
import sqlite3
import tempfile

from library_paging_probe import Cursor, MAX_TIME, Query, initialize, metadata_row, read_page

ROOT = Path(__file__).resolve().parents[1]


def utf16_units(value):
    # Independent comparator, following the existing Kotlin LibraryViewRules ordering.
    result = []
    for char in value:
        scalar = ord(char)
        if scalar < 0x10000:
            result.append(scalar)
        else:
            scalar -= 0x10000
            result += [0xD800 + (scalar >> 10), 0xDC00 + (scalar & 1023)]
    return tuple(result)


def oracle(rows, query):
    selected = [r for r in rows if r[0] == query.space and not r[6]
                and (not query.favorites_only or r[5]) and (query.sort != 'RECENT' or r[4] is not None)]
    def key(r):
        primary = utf16_units(r[2]) if query.sort == 'NAME' else -(r[3] if query.sort == 'UPDATED' else r[4])
        return primary, utf16_units(r[1])
    return [tuple(r[1:6]) for r in sorted(selected, key=key)]


def main():
    output = ROOT/'build/library-paging-20261029'
    output.mkdir(parents=True, exist_ok=True)
    observations, plans, reads = [], set(), set()
    # Repeated keys, canonical-equivalent spellings, supplementary/BMP ordering, extreme timestamps.
    titles = ['中文笔记', 'Alpha', 'alpha', '', '同名', '\U0001f600', '\ue000', 'e\u0301', '\u00e9']
    notes = [('primary', f'n-{i:05d}', titles[i % len(titles)],
              (0 if i % 31 == 0 else MAX_TIME if i % 31 == 1 else i % 97),
              None if i % 7 == 0 else (MAX_TIME if i % 29 == 0 else i % 61),
              i % 3 == 0, i % 11 == 0) for i in range(10_000)]
    # Mixed-Unicode identities exercise the final tie-break independently of title collation.
    notes += [('primary', name, '同名', 42, 42, True, False) for name in ('id-\U0001f600', 'id-\ue000')]
    notes += [('other', 'foreign', 'Alpha', MAX_TIME, MAX_TIME, True, False)]
    with tempfile.TemporaryDirectory(prefix='library-keyset-', dir=output) as temporary:
        path = Path(temporary)/'fixture.sqlite'
        db = sqlite3.connect(path)
        other = None
        try:
            initialize(db)
            db.executemany('INSERT INTO space_revision VALUES(?,0)', [('primary',), ('other',), ('empty',)])
            db.executemany('INSERT INTO note_meta VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                           [metadata_row(*r) for r in notes])
            db.execute('INSERT INTO document_body VALUES(?,?)', ('n-00000', b'body-not-for-metadata'*100_000))
            db.execute('INSERT INTO image_resource VALUES(?,?)', ('synthetic-image', b'image-not-for-metadata'*100_000))
            db.commit()
            def authorize(action, table, column, database, trigger):
                if action == sqlite3.SQLITE_READ:
                    reads.add(table)
                    if table in ('document_body', 'image_resource'):
                        return sqlite3.SQLITE_DENY
                return sqlite3.SQLITE_OK
            db.set_authorizer(authorize)
            # Verify the guard is active, rather than just recording no reads.
            for table in ('document_body', 'image_resource'):
                try:
                    db.execute('SELECT payload FROM '+table).fetchall()
                except sqlite3.DatabaseError:
                    pass
                else:
                    raise AssertionError('Payload authorizer did not deny '+table)
            reads.clear()
            binary_order = [r[0] for r in db.execute(
                "SELECT title FROM note_meta WHERE space='primary' AND note_id IN ('n-00005','n-00006') ORDER BY title COLLATE BINARY")]
            kotlin_order = ['\U0001f600', '\ue000']
            assert binary_order == list(reversed(kotlin_order))
            assert sorted(binary_order, key=utf16_units) == kotlin_order
            for sort in ('NAME', 'UPDATED', 'RECENT'):
                for favorite in (False, True):
                    q = Query('primary', sort, favorite)
                    expected = oracle(notes, q)
                    actual, cursor, work = [], None, []
                    while True:
                        steps = [0]
                        def progress():
                            steps[0] += 1
                            return 0
                        db.set_progress_handler(progress, 1)
                        page = read_page(db, q, 37, cursor)
                        db.set_progress_handler(None, 0)
                        assert len(page.rows) <= 37
                        assert len(page.query_plan) == 1, page.query_plan
                        assert all('USING COVERING INDEX page_' in p and 'TEMP B-TREE' not in p for p in page.query_plan), page.query_plan
                        plans.update(page.query_plan)
                        work.append(steps[0])
                        actual.extend(page.rows)
                        if page.next_cursor is None:
                            break
                        assert page.next_cursor != cursor
                        cursor = page.next_cursor
                    assert actual == expected, (sort, favorite, len(actual), len(expected))
                    assert len({row[0] for row in actual}) == len(actual)
                    # A deep page must seek by both keys, not rescan all preceding rows.
                    assert max(work) < 2500, (sort, favorite, max(work))
                    observations.append({'sort': sort, 'favorites_only': favorite, 'rows': len(actual),
                        'pages': len(work), 'maximum_rows_per_page': 37,
                        'first_page_vm_steps': work[0], 'last_page_vm_steps': work[-1],
                        'maximum_page_vm_steps': max(work),
                        'ordered_rows_sha256': hashlib.sha256(json.dumps(actual, ensure_ascii=False).encode()).hexdigest()})
            offset_steps = [0]
            def offset_progress():
                offset_steps[0] += 1
                return 0
            db.set_progress_handler(offset_progress, 1)
            offset_rows = db.execute('SELECT note_id,title,updated,opened,favorite FROM note_meta '
                                     "WHERE space='primary' AND trashed=0 ORDER BY updated_key,id_key LIMIT 37 OFFSET 8000").fetchall()
            db.set_progress_handler(None, 0)
            assert offset_rows == oracle(notes, Query('primary', 'UPDATED'))[8000:8037]
            keyset_max = next(r['maximum_page_vm_steps'] for r in observations
                              if r['sort'] == 'UPDATED' and not r['favorites_only'])
            assert offset_steps[0] > keyset_max * 10
            assert read_page(db, Query('empty', 'NAME')).rows == ()
            assert [r[0] for r in read_page(db, Query('other', 'NAME')).rows] == ['foreign']
            def refused(query, cursor=None, limit=37, reason='STALE_REVISION'):
                try:
                    read_page(db, query, limit, cursor)
                except ValueError as error:
                    assert str(error) == reason, (reason, error)
                else:
                    raise AssertionError('Expected '+reason)
                assert not db.in_transaction
            q = Query('primary', 'UPDATED')
            cursor = read_page(db, q, 37).next_cursor
            assert isinstance(cursor, Cursor)
            for changed in (Query('other', 'UPDATED'), Query('primary', 'NAME'), Query('primary', 'UPDATED', True)):
                refused(changed, cursor, reason='WRONG_QUERY_CURSOR')
            refused(q, replace(cursor, order_key=b'forged'), reason='INVALID_CURSOR')
            refused(q, replace(cursor, id_key=b'forged'), reason='INVALID_CURSOR')
            refused(q, replace(cursor, note_id='missing'), reason='INVALID_CURSOR')
            for limit in (0, 201, True):
                refused(q, limit=limit, reason='INVALID_QUERY')
            refused(Query('missing', 'NAME'), reason='WRONG_SPACE')
            other = sqlite3.connect(path)
            # Metadata update deliberately interleaves after version read. WAL keeps this page
            # wholly on the old snapshot; the next transaction must reject its old cursor.
            moved = metadata_row('primary', 'n-00002', '改名', MAX_TIME, 60, True, False)
            def mutate():
                other.execute('UPDATE note_meta SET title=?,updated=?,opened=?,favorite=?,name_key=?,updated_key=?,recent_key=? '
                              'WHERE space=? AND note_id=?',
                              (moved[2], moved[3], moved[4], moved[5], moved[7], moved[8], moved[9], moved[0], moved[1]))
                other.commit()
            old_page = read_page(db, q, 37, cursor, after_version_read=mutate)
            assert list(old_page.rows) == oracle(notes, q)[37:74]
            refused(q, old_page.next_cursor)
            notes[2] = ('primary', 'n-00002', '改名', MAX_TIME, 60, True, False)
            assert list(read_page(db, q, 200).rows) == oracle(notes, q)[:200]
            # Deletion and newly inserted rows also invalidate traversal before returning a page.
            for sql, args in [('DELETE FROM note_meta WHERE space=? AND note_id=?', ('primary', 'n-00003')),
                              ('INSERT INTO note_meta VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                               metadata_row('primary', 'new-after-page', '新增', MAX_TIME, None, False, False))]:
                before = read_page(db, q, 37)
                other.execute(sql, args); other.commit()
                refused(q, before.next_cursor)
            notes = [row for row in notes if not (row[0] == 'primary' and row[1] == 'n-00003')]
            notes.append(('primary', 'new-after-page', '新增', MAX_TIME, None, False, False))
            restarted, next_cursor = [], None
            while True:
                page = read_page(db, q, 41, next_cursor)
                restarted.extend(page.rows)
                next_cursor = page.next_cursor
                if next_cursor is None:
                    break
            assert restarted == oracle(notes, q)
            other.execute('UPDATE space_revision SET revision=? WHERE space=?', (MAX_TIME, 'primary'))
            other.commit()
            before_exhaustion = read_page(db, q, 37)
            try:
                other.execute("UPDATE note_meta SET title='must rollback' WHERE space='primary' AND note_id='n-00002'")
            except sqlite3.IntegrityError:
                other.rollback()
            else:
                raise AssertionError('Revision overflow must not become a REAL value')
            after_exhaustion = read_page(db, q, 37)
            assert before_exhaustion == after_exhaustion
            assert reads <= {'fixture_scope', 'space_revision', 'note_meta'}, reads
            assert db.execute('PRAGMA foreign_key_check').fetchall() == []
        finally:
            db.set_progress_handler(None, 0)
            if other is not None:
                other.close()
            db.close()
    report = {'schema': 'library-paging-1', 'status': 'passed', 'date': '2026-10-29',
        'origin': 'synthetic actual host SQLite isolated temporary database', 'sqlite_version': sqlite3.sqlite_version,
        'fixture_primary_rows': 10_002, 'page_size': 37, 'queries': observations,
        'raw_binary_sort_witness': {'sqlite_binary': binary_order, 'existing_kotlin_utf16': kotlin_order,
                                    'orders_differ': True},
        'complete_restart_after_mutations_rows': len(restarted),
        'deep_offset_witness': {'offset': 8000, 'rows': len(offset_rows),
                                'vm_steps': offset_steps[0], 'keyset_maximum_page_vm_steps': keyset_max},
        'query_plans': sorted(plans), 'metadata_tables_read': sorted(reads),
        'checks': ['all_pages_equal_independent_utf16_and_descending_time_oracle',
                   'equal_key_identity_tiebreak_no_duplicates_or_omissions', 'trash_and_space_isolation',
                   'favorite_and_non_null_recent_filters', 'covering_index_no_temporary_sort_no_offset',
                   'bounded_deep_page_vm_steps', 'payload_authorizer_denies_body_and_images',
                   'foreign_query_and_forged_anchor_refused', 'page_limit_and_empty_catalog',
                   'wal_page_is_single_snapshot_during_concurrent_update',
                   'old_cursor_refused_after_update_delete_insert',
                   'complete_restart_matches_mutated_catalog', 'raw_binary_unicode_sort_mismatch_retained',
                   'deep_offset_scan_work_exceeds_keyset_witness',
                   'revision_exhaustion_rolls_back_metadata_without_real_conversion'],
        'decision': 'Adopt metadata-only covering indexes with complete UTF-16/order+identity seek keys and per-page same-transaction revision fences as a candidate. Reject raw SQLite BINARY title ordering as equivalent to the existing Kotlin comparator.',
        'limitations': ['No App integration, production DDL/migration, durable metadata owner, platform SQLite or physical performance claim.',
                       'Revision triggers and encoded sort columns belong to this disposable fixture schema, not a second App authority.',
                       'Global revision rejects unrelated metadata changes conservatively; UI restart/anchor relocation and async covers remain unimplemented.',
                       'No Unicode casefold/locale/natural-sort change; exact existing UTF-16 title order only.',
                       'C1/M-1/heap/stylus and DEV-041 remain gated; LIB-09 is not wholly accepted.'],
        'c1_gate_complete': False, 'production_schema_frozen': False, 'whole_requirement_accepted': False,
        'source_sha256': {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in
                          ['tools/library_paging_probe.py', 'tools/verify_library_paging.py',
                           'shared-core/src/commonMain/kotlin/dev/notecore/library/LibraryViewRules.kt']}}
    raw = output/'result.json'
    raw.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    (ROOT/'reports/library-paging.json').write_bytes(raw.read_bytes())
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
