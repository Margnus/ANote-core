"""Exercise DEV-041 attachment retirement on disposable SQLite and files."""
from contextlib import closing, contextmanager
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
import subprocess
import sys
import tempfile
import time

from verify_resource_gc_rules import QUERY, ROOT

SPACE = 'fixture-space'
PAYLOAD = b'NoteCore synthetic published attachment'
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()
KEY = (SPACE, DIGEST)
CUTS = ('reserved', 'linked', 'source-removed', 'delete-pending', 'tombstone-removed')
SOURCE_NAME = 'published.bin'
TOMBSTONE_NAME = 'retired.bin'
SCHEMA = """
CREATE TABLE resource(id TEXT UNIQUE NOT NULL, space TEXT NOT NULL, hash TEXT NOT NULL,
                      state TEXT NOT NULL, verified INTEGER NOT NULL, promoted_at INTEGER,
                      retirement_device INTEGER, retirement_inode INTEGER,
                      PRIMARY KEY(space,hash));
CREATE TABLE resource_ref(space TEXT NOT NULL, hash TEXT NOT NULL, kind TEXT NOT NULL, owner TEXT NOT NULL,
                          PRIMARY KEY(space,hash,kind,owner), FOREIGN KEY(space,hash) REFERENCES resource(space,hash));
CREATE TRIGGER refuse_reference_to_retiring BEFORE INSERT ON resource_ref
WHEN NOT EXISTS(SELECT 1 FROM resource WHERE space=NEW.space AND hash=NEW.hash AND state='promoted')
BEGIN SELECT RAISE(ABORT,'RESOURCE_NOT_AVAILABLE'); END;
CREATE TABLE task(id TEXT PRIMARY KEY, attempt TEXT NOT NULL, state TEXT NOT NULL);
"""


@contextmanager
def writer(db):
    db.execute('BEGIN IMMEDIATE')
    try:
        yield
        db.execute('COMMIT')
    except BaseException:
        if db.in_transaction:
            db.execute('ROLLBACK')
        raise


def connect(folder):
    db = sqlite3.connect(folder / 'catalog.sqlite', isolation_level=None)
    db.execute('PRAGMA foreign_keys=ON')
    db.execute('PRAGMA synchronous=FULL')
    return db


def state(db):
    return db.execute('SELECT state FROM resource WHERE space=? AND hash=?', KEY).fetchone()[0]


def update(db, value):
    assert db.execute('UPDATE resource SET state=? WHERE space=? AND hash=?', (value, *KEY)).rowcount == 1


def fail_at(stage, cut):
    if stage == cut:
        print(json.dumps({'cut': stage, 'pid': os.getpid()}), flush=True)
        os._exit(73)


def reserved_file_identity(db):
    identity = db.execute('SELECT retirement_device,retirement_inode FROM resource WHERE space=? AND hash=?',
                          KEY).fetchone()
    assert None not in identity
    return identity


def file_identity(path):
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_size != len(PAYLOAD):
        raise ValueError('Unexpected resource type or size')
    if hashlib.sha256(path.read_bytes()).hexdigest() != DIGEST:
        raise ValueError('Unexpected resource contents')
    return (metadata.st_dev, metadata.st_ino)


def retirement(folder, cut):
    source, tombstone = (folder / name for name in (SOURCE_NAME, TOMBSTONE_NAME))
    with closing(connect(folder)) as db:
        with writer(db):
            current = state(db)
            if current == 'promoted':
                if not db.execute(QUERY, (SPACE, 2)).fetchone():
                    return 'PROTECTED'
                try:
                    original = file_identity(source)
                except ValueError:
                    return 'REPAIR_REQUIRED'
                if original is None or os.path.lexists(tombstone):
                    return 'REPAIR_REQUIRED'
                db.execute("""UPDATE resource SET state='retiring',retirement_device=?,retirement_inode=?
                              WHERE space=? AND hash=?""", (*original, *KEY))
        fail_at('reserved', cut)
        with writer(db):
            current = state(db)
            if current == 'retiring':
                expected = reserved_file_identity(db)
                try:
                    source_id, tombstone_id = file_identity(source), file_identity(tombstone)
                    if any(identity is not None and identity != expected for identity in (source_id, tombstone_id)):
                        return 'REPAIR_REQUIRED'
                    if source_id is not None:
                        if tombstone_id is None:
                            os.link(source, tombstone)
                            fail_at('linked', cut)
                            tombstone_id = file_identity(tombstone)
                        if source_id != tombstone_id:
                            return 'REPAIR_REQUIRED'
                        source.unlink()
                        fail_at('source-removed', cut)
                    elif tombstone_id is None:
                        return 'REPAIR_REQUIRED'
                except (ValueError, FileExistsError):
                    return 'REPAIR_REQUIRED'
                update(db, 'delete-pending')
        fail_at('delete-pending', cut)
        with writer(db):
            current = state(db)
            if current == 'retired':
                return 'RETIRED' if not os.path.lexists(source) and not os.path.lexists(tombstone) else 'REPAIR_REQUIRED'
            assert current == 'delete-pending', current
            if os.path.lexists(source):
                return 'REPAIR_REQUIRED'
            try:
                tombstone_id = file_identity(tombstone)
                if tombstone_id is not None:
                    if tombstone_id != reserved_file_identity(db):
                        return 'REPAIR_REQUIRED'
                    tombstone.unlink()
                    fail_at('tombstone-removed', cut)
            except ValueError:
                return 'REPAIR_REQUIRED'
            update(db, 'retired')
        return 'RETIRED'


def reader(folder):
    with closing(connect(folder)) as db:
        reference = (*KEY, 'reader-pin', 'reader-new')
        try:
            with writer(db):
                db.execute('INSERT INTO resource_ref VALUES(?,?,?,?)', reference)
        except sqlite3.IntegrityError as error:
            assert 'RESOURCE_NOT_AVAILABLE' in str(error)
            return 'RESOURCE_NOT_AVAILABLE'
        with (folder / SOURCE_NAME).open('rb') as payload:
            print(json.dumps({'status': 'READ_ADMITTED', 'pid': os.getpid()}), flush=True)
            (folder / 'reader.ready').touch()
            if sys.stdin.readline() != 'release\n':
                raise RuntimeError('Reader lost release signal')
            assert payload.read() == PAYLOAD
        with writer(db):
            assert db.execute('DELETE FROM resource_ref WHERE space=? AND hash=? AND kind=? AND owner=?',
                              reference).rowcount == 1
        return 'RELEASED'


def worker(folder, command):
    if (folder.parent != (ROOT / 'build').resolve() or not folder.name.startswith('dev041-retirement-')
            or (folder / '.fixture-scope').read_bytes() != b'dev041-retirement'):
        raise ValueError('Expected a dedicated synthetic fixture')
    if command == 'reader':
        status = reader(folder)
    elif command in (*CUTS, 'retire'):
        status = retirement(folder, command)
    else:
        raise ValueError(command)
    print(json.dumps({'status': status, 'pid': os.getpid()}), flush=True)


@contextmanager
def fixture():
    with tempfile.TemporaryDirectory(prefix='dev041-retirement-', dir=ROOT / 'build') as temp:
        folder = Path(temp)
        (folder / '.fixture-scope').write_bytes(b'dev041-retirement')
        (folder / 'source-input.bin').write_bytes(PAYLOAD)
        (folder / SOURCE_NAME).write_bytes(PAYLOAD)
        with closing(connect(folder)) as db:
            assert db.execute('PRAGMA journal_mode=WAL').fetchone() == ('wal',)
            db.executescript(SCHEMA)
            db.execute("INSERT INTO resource(id,space,hash,state,verified,promoted_at) VALUES('attachment',?,?,'promoted',1,1)", KEY)
        yield folder


def invoke(folder, command, status=None, cut=None):
    process = subprocess.run([sys.executable, __file__, str(folder), command], input='', encoding='utf-8',
                             capture_output=True, timeout=15)
    assert process.returncode == (73 if cut else 0), (command, process.stderr)
    event = json.loads(process.stdout)
    assert event.get('cut' if cut else 'status') == (cut or status)
    return event


def verify_state(folder, expected, source, tombstone, original):
    with closing(connect(folder)) as db:
        assert state(db) == expected and db.execute('PRAGMA foreign_key_check').fetchall() == []
        assert db.execute('PRAGMA integrity_check').fetchone() == ('ok',)
    assert (folder / 'source-input.bin').read_bytes() == PAYLOAD
    for name, present in ((SOURCE_NAME, source), (TOMBSTONE_NAME, tombstone)):
        assert file_identity(folder / name) == (original if present else None)


def check_crashes():
    observations = []
    for cut, expected in zip(CUTS, (('retiring', True, False), ('retiring', True, True),
                                    ('retiring', False, True), ('delete-pending', False, True),
                                    ('delete-pending', False, False))):
        with fixture() as folder:
            original = file_identity(folder / SOURCE_NAME)
            stopped = invoke(folder, cut, cut=cut)
            verify_state(folder, *expected, original)
            refused = invoke(folder, 'reader', status='RESOURCE_NOT_AVAILABLE')
            recovered = invoke(folder, 'retire', status='RETIRED')
            repeated = invoke(folder, 'retire', status='RETIRED')
            results = [stopped, refused, recovered, repeated]
            assert len({event['pid'] for event in results}) == len(results)
            verify_state(folder, 'retired', False, False, original)
            observations.append({'stage': cut, 'observed_state': expected, 'process_receipts': results})
    return observations


def check_references():
    with fixture() as folder, closing(connect(folder)) as db:
        with writer(db):
            db.execute("INSERT INTO task VALUES('import','first','requested')")
            db.execute("INSERT INTO resource_ref VALUES(?,?,'running-task','first')", KEY)
        assert invoke(folder, 'retire', status='PROTECTED')
        with writer(db):
            assert db.execute("UPDATE task SET state='cancelled' WHERE id='import' AND attempt='stale' AND state='requested'").rowcount == 0
        assert invoke(folder, 'retire', status='PROTECTED')

        # The reader commits its own pin and opens the file before sending the test handshake.
        with subprocess.Popen([sys.executable, __file__, str(folder), 'reader'], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, encoding='utf-8') as child:
            try:
                deadline = time.monotonic() + 15
                while not (folder / 'reader.ready').exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(.05)
                assert (folder / 'reader.ready').exists() and child.poll() is None, 'Reader failed to admit'
                admitted = json.loads(child.stdout.readline())
                assert admitted == {'status': 'READ_ADMITTED', 'pid': child.pid}
                with writer(db):
                    assert db.execute("UPDATE task SET state='cancelled' WHERE id='import' AND attempt='first' AND state='requested'").rowcount == 1
                    assert db.execute("DELETE FROM resource_ref WHERE space=? AND hash=? AND kind='running-task' AND owner='first'", KEY).rowcount == 1
                    assert db.execute("DELETE FROM resource_ref WHERE space=? AND hash=? AND kind='reader-pin' AND owner='reader-old'", KEY).rowcount == 0
                assert invoke(folder, 'retire', status='PROTECTED')
                assert db.execute('SELECT kind,owner FROM resource_ref').fetchall() == [('reader-pin', 'reader-new')]
                output, error = child.communicate('release\n', timeout=15)
                assert child.returncode == 0 and not error
                assert json.loads(output)['status'] == 'RELEASED'
            finally:
                if child.poll() is None:
                    child.kill()
            original = file_identity(folder / SOURCE_NAME)
            invoke(folder, 'retire', status='RETIRED')
            verify_state(folder, 'retired', False, False, original)
            assert db.execute('SELECT state FROM task').fetchone() == ('cancelled',)
    return {'stale_task_confirmed': False, 'task_pin_released_on_confirmation': True,
            'live_reader_blocked_retirement_after_task_cancel': True, 'stale_reader_release_rejected': True,
            'reader_verified_payload_before_release': True}


def check_collisions():
    with fixture() as folder:
        original = file_identity(folder / SOURCE_NAME)
        invoke(folder, 'reserved', cut='reserved')
        old = folder / TOMBSTONE_NAME
        old.write_bytes(PAYLOAD)
        occupied = file_identity(old)
        assert occupied != original
        invoke(folder, 'retire', status='REPAIR_REQUIRED')
        assert file_identity(old) == occupied and file_identity(folder / SOURCE_NAME) == original
        old.unlink()
        hold = folder / 'repair-hold.bin'
        os.link(folder / SOURCE_NAME, hold)
        (folder / SOURCE_NAME).unlink()
        invoke(folder, 'retire', status='REPAIR_REQUIRED')
        verify_state(folder, 'retiring', False, False, original)
        old.write_bytes(PAYLOAD)
        replacement = file_identity(old)
        assert replacement != original
        invoke(folder, 'retire', status='REPAIR_REQUIRED')
        assert file_identity(old) == replacement
        old.unlink()
        os.link(hold, old)
        invoke(folder, 'delete-pending', cut='delete-pending')
        verify_state(folder, 'delete-pending', False, True, original)
        old.unlink()
        old.write_bytes(PAYLOAD)
        replacement = file_identity(old)
        assert replacement != original
        invoke(folder, 'retire', status='REPAIR_REQUIRED')
        assert file_identity(old) == replacement
        old.unlink()
        os.link(hold, old)
        hold.unlink()
        invoke(folder, 'retire', status='RETIRED')
        verify_state(folder, 'retired', False, False, original)
    return {'same_bytes_different_inode_preserved': True, 'moved_and_delete_pending_inode_rechecked': True,
            'unexpected_missing_paths_rejected': True}


def main():
    if len(sys.argv) == 3:
        worker(Path(sys.argv[1]).resolve(), sys.argv[2])
        return
    assert len(sys.argv) == 1
    report = {
        'status': 'passed', 'scope': 'Disposable host SQLite and filesystem with subprocess exits',
        'sqlite_version': sqlite3.sqlite_version, 'crash_and_recovery': check_crashes(),
        'reference_coordination': check_references(), 'occupied_and_missing_paths': check_collisions(),
        'power_loss_verified': False, 'source_sha256': {
            name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
            for name in ('tools/verify_resource_retirement.py', 'tools/verify_resource_gc_rules.py')
        },
    }
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
