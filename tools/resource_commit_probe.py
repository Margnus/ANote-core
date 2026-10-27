#!/usr/bin/env python3
"""Synthetic host-only file/SQLite boundary probe, never imported by the App or shared core."""
import argparse
import hashlib
import json
import os
import re
import sqlite3
import time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
MARKER='notecore-synthetic-resource-commit-fixture-1'
FAULTS=('partial-stage','stage-closed','promoted','db-uncommitted','db-committed')
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def producer_alive(pid):
    # Conservative host probe only. PID reuse may refuse retirement; it never authorizes it.
    if os.name=='nt':
        import ctypes
        from ctypes import wintypes
        api=ctypes.WinDLL('kernel32',use_last_error=True)
        api.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];api.OpenProcess.restype=wintypes.HANDLE
        api.GetExitCodeProcess.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.DWORD)]
        api.CloseHandle.argtypes=[wintypes.HANDLE]
        handle=api.OpenProcess(0x1000,False,pid)
        if not handle:return False if ctypes.get_last_error()==87 else None
        try:
            code=wintypes.DWORD()
            if not api.GetExitCodeProcess(handle,ctypes.byref(code)):return None
            return code.value==259
        finally:api.CloseHandle(handle)
    try:os.kill(pid,0);return True
    except ProcessLookupError:return False
    except PermissionError:return None

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--fault',choices=FAULTS)
    p.add_argument('--action',choices=('commit','cancel-request','cancel-confirm'),default='commit')
    p.add_argument('--hold-after-stage',action='store_true')
    p.add_argument('--attempt',required=True);p.add_argument('--document',default='fixture-doc');args=p.parse_args()
    folder=Path(args.root).resolve()
    assert folder.is_relative_to((ROOT/'build').resolve()) and (folder/'.fixture-scope').read_text(encoding='utf-8')==MARKER
    assert re.fullmatch(r'[a-zA-Z0-9-]{1,64}',args.attempt) and re.fullmatch(r'[a-zA-Z0-9-]{1,64}',args.document)
    source=folder/'source.bin';payload=source.read_bytes();assert 1<len(payload)<=4096
    sha=hashlib.sha256(payload).hexdigest();space='fixture-space';operation='fixture-operation'
    target=folder/'resources'/sha
    db=sqlite3.connect(folder/'catalog.sqlite',isolation_level=None,timeout=2)
    try:
        db.execute('PRAGMA foreign_keys=ON');assert db.execute('PRAGMA journal_mode=WAL').fetchone()[0]=='wal'
        db.execute('PRAGMA synchronous=FULL')
        db.execute('CREATE TABLE IF NOT EXISTS resource(space TEXT,hash TEXT,bytes INTEGER NOT NULL,PRIMARY KEY(space,hash))')
        db.execute('CREATE TABLE IF NOT EXISTS document(id TEXT PRIMARY KEY,space TEXT NOT NULL,hash TEXT NOT NULL,FOREIGN KEY(space,hash) REFERENCES resource(space,hash))')
        db.execute('CREATE TABLE IF NOT EXISTS operation(id TEXT PRIMARY KEY,document TEXT NOT NULL,hash TEXT NOT NULL)')
        db.execute('CREATE TABLE IF NOT EXISTS import_task(id TEXT PRIMARY KEY,state TEXT NOT NULL,hash TEXT,cancel_requested INTEGER NOT NULL DEFAULT 0,active_attempt TEXT,producer_pid INTEGER)')
        db.execute('BEGIN IMMEDIATE')
        prior=db.execute('SELECT document,hash FROM operation WHERE id=?',(operation,)).fetchone()
        def reply(status):print(json.dumps({'status':status,'hash':sha,'host_only':True}),flush=True)
        if prior:
            if prior!=(args.document,sha):reply('IDENTITY_CONFLICT');return
            if not target.is_file() or target.stat().st_size!=len(payload) or digest(target)!=sha:
                reply('REPAIR_REQUIRED');return
            reply('ALREADY_COMMITTED');return
        task=db.execute('SELECT state,cancel_requested,active_attempt,producer_pid FROM import_task WHERE id=?',(operation,)).fetchone()
        if task and task[0]=='cancelled':db.execute('COMMIT');reply('CANCELLED');return
        if args.action!='commit':
            if not task or task[2]!=args.attempt:db.execute('COMMIT');reply('STALE_ATTEMPT');return
            if args.action=='cancel-request':
                db.execute('UPDATE import_task SET cancel_requested=1 WHERE id=?',(operation,))
                db.execute('COMMIT');reply('CANCEL_REQUESTED');return
            if not task[1]:db.execute('COMMIT');reply('NO_CANCEL_REQUEST');return
            alive=producer_alive(task[3])
            if alive is not False:
                db.execute('COMMIT');reply('PRODUCER_ACTIVE' if alive else 'PRODUCER_UNKNOWN');return
            db.execute("UPDATE import_task SET state='cancelled' WHERE id=?",(operation,))
            db.execute('COMMIT');reply('CANCELLED');return
        if task and task[1]:db.execute('COMMIT');reply('CANCEL_REQUESTED');return
        if task and producer_alive(task[3]) is not False:
            db.execute('COMMIT');reply('PRODUCER_ACTIVE');return
        db.execute("INSERT OR IGNORE INTO import_task(id,state) VALUES(?,'running')",(operation,))
        db.execute('UPDATE import_task SET active_attempt=?,producer_pid=? WHERE id=?',(args.attempt,os.getpid(),operation))
        db.execute('COMMIT')
        def acknowledge_cancel():
            current=db.execute('SELECT cancel_requested,active_attempt FROM import_task WHERE id=?',(operation,)).fetchone()
            assert current[1]==args.attempt
            if current[0]:
                db.execute("UPDATE import_task SET state='cancelled' WHERE id=?",(operation,));return True
            return False
        stage=folder/'staging'/(args.attempt+'.bin');stage.parent.mkdir(exist_ok=True)
        with stage.open('xb') as output:
            if args.fault=='partial-stage':
                output.write(payload[:len(payload)//2]);output.flush();os.fsync(output.fileno());os._exit(73)
            output.write(payload);output.flush();os.fsync(output.fileno())
        assert stage.stat().st_size==len(payload) and digest(stage)==sha
        db.execute('UPDATE import_task SET hash=? WHERE id=?',(sha,operation))
        if args.fault=='stage-closed':os._exit(73)
        if args.hold_after_stage:
            (folder/(args.attempt+'.ready')).write_text('ready',encoding='utf-8',newline='\n')
            deadline=time.monotonic()+10
            while not (folder/(args.attempt+'.release')).exists():
                if time.monotonic()>deadline:os._exit(73) # Retain ownership on timeout.
                time.sleep(0.01)
        db.execute('BEGIN IMMEDIATE')
        cancelled=acknowledge_cancel();db.execute('COMMIT')
        if cancelled:reply('CANCELLED');return
        target.parent.mkdir(exist_ok=True)
        try:os.link(stage,target) # Atomic no-clobber link on this host; no portability/directory-fsync claim.
        except FileExistsError:
            if not target.is_file() or target.stat().st_size!=len(payload) or digest(target)!=sha:
                reply('REPAIR_REQUIRED');return
        assert digest(target)==sha
        if args.fault=='promoted':os._exit(73)
        db.execute('BEGIN IMMEDIATE')
        if acknowledge_cancel():db.execute('COMMIT');reply('CANCELLED');return
        db.execute('INSERT OR IGNORE INTO resource VALUES(?,?,?)',(space,sha,len(payload)))
        db.execute('INSERT INTO document VALUES(?,?,?)',(args.document,space,sha))
        db.execute('INSERT INTO operation VALUES(?,?,?)',(operation,args.document,sha))
        db.execute("UPDATE import_task SET state='complete' WHERE id=?",(operation,))
        if args.fault=='db-uncommitted':os._exit(73)
        db.execute('COMMIT')
        if args.fault=='db-committed':os._exit(73)
        reply('COMMITTED')
    finally:db.close()
if __name__=='__main__':main()
