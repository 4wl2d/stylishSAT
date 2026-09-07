#!/usr/bin/env python3
"""Independent content gates. Does not certify editorial/psychometric quality."""
from __future__ import annotations
from collections import Counter, defaultdict
from fractions import Fraction
from pathlib import Path
import argparse, hashlib, json, math, re, wave

ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets'
PACK_FIELDS={'schemaVersion','id','version','title','skills','lessons','exercises','reviewStatus'}
SKILL_FIELDS={'id','exam','title','section','description','prerequisites'}
LESSON_FIELDS={'id','skillId','title','body','workedExample','estimatedMinutes'}
EX_FIELDS={'id','version','exam','skillId','difficulty','split','familyId','sourceId','type','prompt','passage','options','acceptedAnswers','hints','explanation','typicalErrors','expectedSeconds','wordLimit','minWords','author','audioAssetPath','transcript','transcriptSegments','evidence','criteria','sampleAnswer','chart'}
SPLITS={'DIAGNOSTIC','PRACTICE','ASSESSMENT'}
TYPES={'MULTIPLE_CHOICE','NUMERIC','SHORT_ANSWER','WRITING','SPEAKING'}

def require(condition,message):
    if not condition: raise ValueError(message)
def fields(obj,allowed,where):
    require(isinstance(obj,dict),f'{where}: expected object')
    require(set(obj)<=allowed,f'{where}: unknown fields {set(obj)-allowed}')
def bilingual(obj,where):
    fields(obj,{'en','ru'},where)
    require(set(obj)=={'en','ru'} and all(isinstance(x,str) and x.strip() for x in obj.values()),f'{where}: missing bilingual text')
def numeric(value):
    require(bool(re.fullmatch(r'-?(?:\d+(?:\.\d+)?|\d+/\d+)',value)),f'Invalid numeric key {value!r}')
    return Fraction(value)

def validate(path):
    pack_bytes=path.read_bytes()
    def no_duplicates(pairs):
        obj={}
        for k,v in pairs:
            require(k not in obj,f'Duplicate JSON key: {k}')
            obj[k]=v
        return obj
    pack=json.loads(path.read_text(),object_pairs_hook=no_duplicates,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Non-finite JSON number: '+value)))
    fields(pack,PACK_FIELDS,'pack')
    require(pack['schemaVersion']==1,'Unsupported schema version')
    require(pack['reviewStatus']=='AI_DRAFT_MACHINE_VALIDATED','Pilot must not claim expert review')
    require(pack['version']==2,'Expected frozen package version2')
    bilingual(pack['title'],'pack.title')
    skills={s['id']:s for s in pack['skills']}
    require(len(skills)==len(pack['skills'])==12,'Expected12 unique skills')
    require(Counter(s['exam'] for s in skills.values())=={'SAT':8,'IELTS':4},'Skill coverage changed')
    for sk in skills.values():
        fields(sk,SKILL_FIELDS,sk['id'])
        bilingual(sk['title'],sk['id']+'.title');bilingual(sk['description'],sk['id']+'.description')
        require(all(k in skills for k in sk.get('prerequisites',[])),'Unknown prerequisite')
    require(len({l['id'] for l in pack['lessons']})==len(pack['lessons'])==12,'Expected12 unique lessons')
    for l in pack['lessons']:
        fields(l,LESSON_FIELDS,l['id']);require(l['skillId'] in skills,'Unknown lesson skill')
        for k in ['title','body','workedExample']: bilingual(l[k],l['id']+'.'+k)
        require(len(l['body']['en'].split())>=35 and len(l['workedExample']['en'].split())>=20,'Lesson lacks substantive explanation/example')
    exercises=pack['exercises']; byid={e['id']:e for e in exercises}
    require(len(byid)==len(exercises)==87,'Expected87 unique pilot exercises')
    grouping={key:defaultdict(set) for key in ['familyId','sourceId','passage','audioAssetPath']}
    shape_counts=Counter()
    audio_manifest=json.loads((ROOT/'docs/content/audio-manifest.json').read_text())
    chart_sources=json.loads((ROOT/'docs/content/chart-source-data.json').read_text())
    for e in exercises:
        id=e['id']; fields(e,EX_FIELDS,id)
        require(e['exam'] in ['SAT','IELTS'] and skills[e['skillId']]['exam']==e['exam'],id+': skill/exam mismatch')
        require(e['split'] in SPLITS and e['type'] in TYPES,id+': invalid enum')
        require(e['difficulty'] in [1,2,3] and e['expectedSeconds']>0,id+': invalid difficulty/time')
        require(e['version']==2 and 'expert review pending' in e['author'],id+': missing authorship/review status')
        bilingual(e['explanation'],id+'.explanation')
        require(len(e['hints'])>=2 and len(e['typicalErrors'])>=1,id+': missing staged help')
        for k in ['hints','typicalErrors','criteria']:
            for i,t in enumerate(e.get(k,[])):bilingual(t,f'{id}.{k}[{i}]')
        for k,g in grouping.items():
            if e.get(k):g[e[k]].add(e['split'])
        shape_counts[(e['skillId'],e['split'])]+=1
        require(e['prompt'].strip(),id+': empty prompt')
        if e['type']=='MULTIPLE_CHOICE':
            require(len(e['options'])==4==len(set(e['options'])),id+': options must be distinct')
            require(len(e['acceptedAnswers'])==1 and e['acceptedAnswers'][0] in e['options'],id+': invalid MC key')
        elif e['type']=='NUMERIC':
            for key in e['acceptedAnswers']:numeric(key)
        elif e['type']=='SHORT_ANSWER':
            require(e.get('wordLimit',0)>0,id+': missing word limit')
            require(e['acceptedAnswers'],id+': no prepared key')
            for key in e['acceptedAnswers']:
                require(len(key.split())<=e['wordLimit'],id+': key exceeds word limit')
        else:
            require(not e['acceptedAnswers'],id+': open response must not have a closed key')
            require(len(e.get('criteria',[]))==4 and e.get('sampleAnswer'),id+': missing criteria/sample')
            if e['type']=='WRITING':
                minimum=150 if e.get('chart') else 250
                require(len(e['sampleAnswer'].split())>=minimum,id+': sample too short')
            if e['type']=='SPEAKING':
                require(all(p in e['prompt'] for p in ['Part 1:','Part 2:','Part 3:']),id+': incomplete speaking set')
        if e['skillId']=='ielts_reading':
            require(e.get('evidence') and e['evidence'] in e['passage'],id+': passage evidence is not an exact excerpt')
        if e['skillId']=='ielts_listening':
            clip=audio_manifest['clips'][e['sourceId']]
            require(e['audioAssetPath']==clip['audioAssetPath'],id+': audio link mismatch')
            require(e.get('transcriptSegments')==clip['transcriptSegments'],id+': timing manifest mismatch')
            require(e['transcript']==' '.join(t['text'] for t in e['transcriptSegments']),id+': transcript mismatch')
            require(e['evidence'] in e['transcript'],id+': missing transcript evidence')
            require(e['split']==clip['split'],id+': audio split mismatch')
            require('synthetic practice recording' in e['transcript'],id+': synthetic label missing')
        if e.get('chart'):
            chart=e['chart'];fields(chart,{'title','xLabel','yLabel','unit','labels','series'},id+'.chart')
            require(chart==chart_sources[e['familyId']],id+': chart does not match authored source data')
            for series in chart['series']:
                fields(series,{'name','values'},id+'.chart.series')
                require(len(series['values'])==len(chart['labels']),id+': chart dimension mismatch')
                require(all(isinstance(v,(int,float)) and math.isfinite(v) for v in series['values']),id+': invalid chart value')
    for k,g in grouping.items():
        require(all(len(splits)==1 for splits in g.values()),f'{k}: content leaked across splits')
    for sk in skills.values():
        require(all(shape_counts[(sk['id'],split)]>0 for split in SPLITS),sk['id']+': missing split')
        if sk['exam']=='SAT':
            require(shape_counts[(sk['id'],'DIAGNOSTIC')]==2,sk['id']+': need2 diagnostic probes')
            require({e['difficulty'] for e in exercises if e['skillId']==sk['id']}=={1,2,3},sk['id']+': missing difficulty')
    # Expected results independently recomputed here, outside the authoring functions.
    expected={
      'sat-algebra-d1':Fraction(23+7,5),
      'sat-algebra-d2':Fraction(11+4,3),
      'sat-algebra-p3':Fraction(7)-Fraction(-5-7,4-(-2))*-2,
      'sat-algebra-a1':Fraction(2)*Fraction(9,3),
      'sat-advanced-d1':max(x for x in range(-10,11) if x*x-9*x+20==0),
      'sat-advanced-d2':min((x-3)**2+8 for x in range(-10,11)),
      'sat-advanced-p1':80*2**3,
      'sat-advanced-p3':next(x for x in range(10) if math.sqrt(x+6)==x),
      'sat-advanced-a1':Fraction((-6)**2,4),
      'sat-data-d1':80*(1-Fraction(15,100)),
      'sat-data-d2':Fraction(sum([6,8,9,11,16]),5),
      'sat-data-p1':Fraction(36,4)*7,
      'sat-data-p2':Fraction(9,12),
      'sat-data-a1':sorted([x+4 for x in [2,5,7,9,12]])[2]-7,
      'sat-geometry-d1':Fraction(10,2)**2,
      'sat-geometry-d2':math.isqrt(9**2+12**2),
      'sat-geometry-p1':3**2*5,
      'sat-geometry-p2':24*Fraction(10,4)**2,
      'sat-geometry-p3':Fraction(math.isqrt(13**2-5**2),13),
      'sat-geometry-a1':Fraction(180-10+6,3+5),
    }
    require(set(expected)=={e['id'] for e in exercises if e['type']=='NUMERIC'},'Missing independent numeric verification')
    for id,answer in expected.items():
        require(all(numeric(k)==answer for k in byid[id]['acceptedAnswers']),id+': independent numerical solution disagrees')
    for x in range(-7,9):
        require((14-3*x>=2)==(x<=4),'Inequality derivation failed')
        if x!=5:require(Fraction(x*x-25,x-5)==x+5,'Rational simplification failed')
    require(byid['sat-algebra-p1']['acceptedAnswers']==['4h + 9'],'Rental model key mismatch')
    require(byid['sat-algebra-p2']['acceptedAnswers']==['x ≤ 4'],'Inequality option key mismatch')
    require(byid['sat-advanced-p2']['acceptedAnswers']==['x + 5'],'Rational option key mismatch')
    require(byid['sat-data-p3']['acceptedAnswers']==['Students were randomly assigned to schedules.'],'Study design option key mismatch')
    for source,clip in audio_manifest['clips'].items():
        path=ASSETS/clip['audioAssetPath']
        require(path.resolve().is_relative_to(ASSETS.resolve()),'Unsafe asset path')
        require(hashlib.sha256(path.read_bytes()).hexdigest()==clip['sha256'],source+': audio checksum mismatch')
        with wave.open(str(path)) as w:
            require(w.getnchannels()==1 and w.getsampwidth()==2 and w.getframerate()==22050,source+': unsupported PCM format')
            duration=round(w.getnframes()*1000/w.getframerate())
            require(duration==clip['durationMs'] and duration>1000,source+': invalid audio duration')
        end=0
        for seg in clip['transcriptSegments']:
            fields(seg,{'startMs','endMs','text'},source+'.segment')
            require(end<=seg['startMs']<seg['endMs']<=duration,source+': invalid segment timing')
            end=seg['endMs']
    report={
      'schemaVersion':1,'contentId':pack['id'],'contentVersion':pack['version'],
      'reviewStatus':pack['reviewStatus'],'sha256':hashlib.sha256(pack_bytes).hexdigest(),
      'counts':{'exercises':len(exercises),'skills':len(skills),'lessons':len(pack['lessons']),'sat':sum(e['exam']=='SAT' for e in exercises),'ielts':sum(e['exam']=='IELTS' for e in exercises),'audioClips':len(audio_manifest['clips']),'charts':sum(bool(e.get('chart')) for e in exercises),'numericKeysIndependentlyRecomputed':len(expected)},
      'splits':dict(Counter(e['split'] for e in exercises)),
      'bySkill':{id:dict(Counter(e['split'] for e in exercises if e['skillId']==id)) for id in skills},
      'passed':['Strict known fields and duplicate JSON keys','Bilingual lessons, explanations, staged hints, error notes','All12 skills across3 splits and16 SAT diagnostic probes','Source, family, exact passage and audio split isolation','20 numeric keys independently recomputed plus4 Math MC checks','Reading evidence exact excerpt and short-answer limits','3 charts match authoring data; samples meet full task length','3 bundled WAV checksums and15 segment timings'],
      'limitations':['No independent human expert review or calibration','Family IDs/exact text checks do not prove semantic separation','Synthetic audio has no expert listening-quality approval','No official score/band or model-quality metric is established']}
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack',type=Path,default=ROOT/'tools/content/pilot/seed-v2.json')
    parser.add_argument('--report',type=Path,default=ROOT/'docs/content/validation-report.json')
    args=parser.parse_args()
    result=validate(args.pack)
    args.report.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'status':'PASS','counts':result['counts'],'splits':result['splits'],'report':str(args.report)},ensure_ascii=False))
