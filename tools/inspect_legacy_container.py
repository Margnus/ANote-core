#!/usr/bin/env python3
"""Bounded read-only legacy container framing diagnostic; never imports records or extracts paths."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path
LIMIT=16*1024*1024
KNOWN={'NOTE','PAGE','OUTLINE','NOTE_GOODS','STROKE','TEXT','IMAGE','AUDIO_EX','AUDIO','ASR','COVER','THUMB','PDF','OTHER','NO_LEVEL_LOCAL_FILE','NBSTROKE','NBTHUMB'}
class FramingError(ValueError):pass

def inspect_bytes(data):
    if len(data)>LIMIT:raise FramingError('DIAGNOSTIC_LIMIT')
    offset=0
    def take(size):
        nonlocal offset
        if size<0 or size>len(data)-offset:raise FramingError('TRUNCATED_OR_INVALID_LENGTH')
        result=data[offset:offset+size];offset+=size;return result
    def number(fmt):return struct.unpack(fmt,take(struct.calcsize(fmt)))[0]
    def utf():
        raw=take(number('>H'));units=[];i=0
        while i<len(raw):
            first=raw[i];i+=1
            if 1<=first<=127:units.append(first);continue
            count=1 if 0xc0<=first<=0xdf else 2 if 0xe0<=first<=0xef else -1
            if count<0 or i+count>len(raw):raise FramingError('MODIFIED_UTF_INVALID')
            tail=raw[i:i+count];i+=count
            if any(value&0xc0!=0x80 for value in tail):raise FramingError('MODIFIED_UTF_INVALID')
            value=first & (0x1f if count==1 else 0x0f)
            for part in tail:value=(value<<6)|(part&0x3f)
            if (count==1 and value<128 and value!=0) or (count==2 and value<2048):raise FramingError('MODIFIED_UTF_INVALID')
            units.append(value)
        return b''.join(struct.pack('>H',unit) for unit in units).decode('utf-16-be',errors='surrogatepass')
    if utf()!='TRY':raise FramingError('NOT_JNOTES_CONTAINER')
    version=number('>i')
    if version not in (1,2):raise FramingError('UNSUPPORTED_VERSION')
    utf() # Source note identity is deliberately not logged.
    records=[]
    while True:
        link=number('>i')
        if link==0:break
        if link!=1:raise FramingError('INVALID_LINK')
        if len(records)>=10000:raise FramingError('DIAGNOSTIC_LIMIT')
        kind,location,name=utf(),utf(),utf();length=number('>q')
        utf();utf();utf() # parent identity/path/description; never use them as host paths.
        payload=take(length)
        records.append({'type':kind,'location':location,'bytes':length,'payload_sha256':hashlib.sha256(payload).hexdigest(),
                        'name_sha256':hashlib.sha256(name.encode('utf-16-be',errors='surrogatepass')).hexdigest(),
                        'known_type':kind in KNOWN,'known_location':location in ('DB','LOCAL')})
    tail_start=offset;tail_version=number('>i');suffix=utf();tail_length=number('>q')
    if tail_version!=version or suffix!='Lucky' or tail_length!=19 or offset-tail_start!=19 or offset!=len(data):raise FramingError('INVALID_TAIL_OR_TRAILING_DATA')
    return {'framing_valid':True,'version':version,'records':records,'unknown_record_indices':[i for i,r in enumerate(records) if not r['known_type'] or not r['known_location']],
            'migration_supported':False,'payloads_deserialized':False,'source_names_logged':False}

def inspect_archive(path):
    if path.stat().st_size>LIMIT:raise FramingError('DIAGNOSTIC_LIMIT')
    with zipfile.ZipFile(path) as archive:
        entries=archive.infolist()
        if len(entries)!=1 or entries[0].filename!='zip.Jzip' or entries[0].file_size>LIMIT or entries[0].flag_bits&1:raise FramingError('UNSUPPORTED_ARCHIVE_LAYOUT')
        with archive.open(entries[0]) as stream:data=stream.read(LIMIT+1)
    return inspect_bytes(data)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('archive',type=Path);args=parser.parse_args()
    before=hashlib.sha256(args.archive.read_bytes()).hexdigest() if args.archive.stat().st_size<=LIMIT else None
    try:report=inspect_archive(args.archive)
    except (FramingError,zipfile.BadZipFile) as error:report={'framing_valid':False,'reason':str(error),'migration_supported':False}
    if before is not None:assert hashlib.sha256(args.archive.read_bytes()).hexdigest()==before
    report.update(source_sha256=before,scope='Local read-only framing diagnostic; no JNotes payload migration, extraction or App integration.')
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
