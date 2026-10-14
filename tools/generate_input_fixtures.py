#!/usr/bin/env python3
"""Deterministic synthetic traces for D2. They are NOT measured hardware/performance data."""
import argparse
import hashlib
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEED = 20261014

def sample(index, offset=0):
    return {'x': round(20 + index * 0.75 + offset, 6),
            'y': round(40 + math.sin(index / 8) * 10, 6),
            'pressure': round(0.3 + (index % 16) / 32, 6),
            'time_us': index * 4167, 'tilt_radians': 0.0}

def stroke_events(points, predictions=False):
    events=[]
    for i in range(points):
        point=sample(i)
        event={'sequence':i,'time_us':point['time_us'],'type':'pointer', 'pointer_id':1,
               'tool':'stylus','phase':'down' if i==0 else 'up' if i==points-1 else 'move',
               'real_samples':[point], 'predicted_samples':[]}
        if predictions and event['phase']=='move':
            event['predicted_samples']=[sample(i+1, offset=0.25),sample(i+2, offset=0.5)]
        events.append(event)
    return events

def case(name,events,completed,count,target='layer-at-down'):
    # All positions/time are fabricated. real_samples means non-predicted within this simulation.
    return {'id':name,'target_at_down':{'scene_id':'fixture-page','layer_id':'layer-at-down',
            'brush':{'kind':'monoline','algorithm_version':1,'width':2.0}},
            'coordinate_snapshot':{'a':1.0,'b':0.0,'c':0.0,'d':1.0,'tx':0.0,'ty':0.0},
            'events':events,'expected':{'completed_strokes':completed,'committed_real_samples':count,
            'committed_layer':target if completed else None,'prediction_persisted':False}}

def document(points):
    normal=stroke_events(points)
    prediction=stroke_events(points,True)
    switched=stroke_events(points)
    switched.append({'sequence':points,'time_us':points*4167,'type':'select_layer','layer_id':'later-ui-layer'})
    switched.append({'sequence':points+1,'time_us':points*4167+1000,'type':'complete_pending_commit'})
    cancelled=stroke_events(points)
    cancelled[-1]['phase']='cancel'
    navigation=stroke_events(points)
    finger={'sequence':0,'time_us':0,'type':'pointer','pointer_id':2,'tool':'finger','phase':'down',
            'real_samples':[dict(sample(0),x=500.0,y=500.0)],'predicted_samples':[]}
    navigation.insert(1,finger)
    for i,event in enumerate(navigation):event['sequence']=i
    return {'schema_version':'0.1-draft','origin':'synthetic','seed':SEED,
            'measured_device':None,'measured_latency_us':None,
            'units':{'coordinates':'scene-point','time':'microsecond','pressure':'normalized-0-to-1'},
            'input_policy':'stylus-only','checkpoint_policy':'none-in-this-fixture',
            'cases':[case('plain-stroke',normal,1,points),case('prediction-is-preview-only',prediction,1,points),
                     case('layer-change-after-up',switched,1,points),case('cancel-before-commit',cancelled,0,0),
                     case('finger-navigation-is-not-ink',navigation,1,points)]}

def validate(doc):
    assert doc['origin']=='synthetic' and doc['measured_device'] is None
    assert doc['measured_latency_us'] is None
    for c in doc['cases']:
        last_event_time=-1
        last_real_time={}
        pending=[]
        completed=[]
        current_layer=c['target_at_down']['layer_id']
        captured_layer=None
        for seq,event in enumerate(c['events']):
            assert event['sequence']==seq
            assert event['time_us']>=last_event_time
            last_event_time=event['time_us']
            if event['type']=='select_layer':
                current_layer=event['layer_id']
                continue
            if event['type']!='pointer':continue
            pointer=event['pointer_id']
            for p in event['real_samples']:
                assert all(math.isfinite(p[key]) for key in ['x','y','pressure','tilt_radians'])
                assert 0<=p['pressure']<=1
                assert last_real_time.get(pointer,-1)<=p['time_us']<=event['time_us']
                last_real_time[pointer]=p['time_us']
            for p in event['predicted_samples']:
                assert all(math.isfinite(p[key]) for key in ['x','y','pressure','tilt_radians'])
                assert 0<=p['pressure']<=1
                assert p['time_us']>event['time_us']
            if event['tool']!='stylus':continue
            if event['phase']=='down':captured_layer=current_layer
            pending.extend(event['real_samples'])
            if event['phase']=='cancel':pending=[]
            elif event['phase']=='up':completed.append((pending,captured_layer));pending=[]
        assert len(completed)==c['expected']['completed_strokes']
        assert sum(len(points) for points,_ in completed)==c['expected']['committed_real_samples']
        assert all(layer==c['expected']['committed_layer'] for _,layer in completed)
        assert c['expected']['prediction_persisted'] is False
    return len(doc['cases'])

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--points',type=int,default=96)
    ap.add_argument('--output',type=Path,default=ROOT/'fixtures/input');args=ap.parse_args()
    if not 2<=args.points<=100_000:raise SystemExit('--points must be between 2 and 100000')
    doc=document(args.points);count=validate(doc)
    args.output.mkdir(parents=True,exist_ok=True)
    payload=(json.dumps(doc,ensure_ascii=False,indent=2)+'\n').encode()
    path=args.output/'synthetic-traces.json';path.write_bytes(payload)
    manifest={'schema_version':'0.1-draft','origin':'synthetic','seed':SEED,'cases':count,
              'points_per_stroke':args.points,'file':'synthetic-traces.json',
              'sha256':hashlib.sha256(payload).hexdigest(),'bytes':len(payload),
              'validated_by':'Python schema/order/reference projection checks; Kotlin/native adapter consumption not implemented',
              'hardware_performance_baseline':False}
    (args.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(manifest,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
