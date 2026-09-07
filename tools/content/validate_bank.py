#!/usr/bin/env python3
"""Full-bank structural and provenance checks. These do not constitute expert acceptance."""
from __future__ import annotations
from collections import Counter, defaultdict
from pathlib import Path
import argparse
import hashlib
import json
import math
import unicodedata
import wave

from validate_pilot import (ROOT, ASSETS, PACK_FIELDS, SKILL_FIELDS, LESSON_FIELDS,
    EX_FIELDS, SPLITS, TYPES, require, fields, bilingual, numeric, validate as validate_pilot)


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f'Duplicate JSON key: {key}')
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique,
        parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Non-finite JSON: ' + value)))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalized(text):
    return ' '.join(unicodedata.normalize('NFKC', text).split())


def validate(path):
    pilot = validate_pilot(ROOT / 'tools/content/pilot/seed-v2.json')
    pack = read(path)
    if path.resolve() == (ASSETS / 'content/seed-v1.json').resolve():
        info = read(ASSETS / 'content/bundle-info.json')
        require(info == {'schemaVersion': 1, 'contentId': pack['id'], 'contentVersion': pack['version'],
            'contentSchemaVersion': pack['schemaVersion'], 'sha256': sha(path)}, 'Bundled version/hash metadata mismatch')
    fields(pack, PACK_FIELDS, 'pack')
    require(pack['schemaVersion'] == 2 and pack['version'] == 4, 'Expected full bank schema2 / package4')
    require(pack['reviewStatus'] == 'AI_DRAFT_MACHINE_VALIDATED', 'Human expert status must remain pending')
    bilingual(pack['title'], 'pack.title')
    skills = {s['id']: s for s in pack['skills']}
    require(len(skills) == len(pack['skills']) == 12, 'Expected12 skills')
    require(Counter(s['exam'] for s in skills.values()) == {'SAT': 8, 'IELTS': 4}, 'Skill count')
    for skill in skills.values():
        fields(skill, SKILL_FIELDS, skill['id'])
        bilingual(skill['title'], skill['id']); bilingual(skill['description'], skill['id'])
        require(all(s in skills for s in skill.get('prerequisites', [])), 'Unknown prerequisite')
    require(len({l['id'] for l in pack['lessons']}) == len(pack['lessons']) == 48, 'Expected48 unique lessons')
    require(Counter(skills[l['skillId']]['exam'] for l in pack['lessons']) == {'SAT': 24, 'IELTS': 24}, 'Lesson exam counts')
    for lesson in pack['lessons']:
        fields(lesson, LESSON_FIELDS, lesson['id'])
        require(lesson['skillId'] in skills and lesson['estimatedMinutes'] > 0, 'Lesson metadata')
        for key in ['title', 'body', 'workedExample']: bilingual(lesson[key], lesson['id'] + '.' + key)
        require(len(lesson['body']['en'].split()) >= 35 and len(lesson['workedExample']['en'].split()) >= 20, 'Substantive lesson content')
    items = pack['exercises']; by_id = {e['id']: e for e in items}
    require(len(by_id) == len(items) == 810, 'Expected810 unique exercises')
    require(Counter(e['skillId'] for e in items) == {
        **{key: 36 for key, s in skills.items() if s['exam'] == 'SAT'},
        'ielts_reading': 240, 'ielts_listening': 240, 'ielts_writing': 24, 'ielts_speaking': 18}, 'Full-bank quantities')
    listening = read(ROOT / 'tools/content/expansion/listening_work/audio-manifest.json')['clips']
    compact = read(ROOT / 'docs/content/compact-audio-manifest.json')['assets']
    spoken = read(ROOT / 'docs/content/speaking-audio-manifest.json')['samples']
    groups = {key: defaultdict(set) for key in ['familyId', 'sourceId', 'passage', 'audioAssetPath']}
    for item in items:
        key = item['id']; fields(item, EX_FIELDS | {'sampleAudioAssetPath'}, key)
        require(item['exam'] == skills[item['skillId']]['exam'], key + ': exam mismatch')
        require(item['split'] in SPLITS and item['type'] in TYPES, key + ': enum')
        require(item['version'] > 0 and item['difficulty'] in [1, 2, 3] and item['expectedSeconds'] > 0, key + ': metadata')
        require(item['prompt'].strip() and item['author'].strip() and 'pending' in item['author'].lower(), key + ': authorship/review status')
        bilingual(item['explanation'], key)
        require(len(item['hints']) >= 2 and len(item['typicalErrors']) >= 1, key + ': staged help')
        for field in ['hints', 'typicalErrors', 'criteria']:
            for value in item.get(field, []): bilingual(value, key + '.' + field)
        for field, grouping in groups.items():
            if item.get(field): grouping[normalized(item[field])].add(item['split'])
        if item['type'] == 'MULTIPLE_CHOICE':
            require(len(item['options']) == len(set(item['options'])) and (len(item['options']) == 4 if item['exam'] == 'SAT' else 3 <= len(item['options']) <= 4), key + ': option uniqueness')
            require(len(item['acceptedAnswers']) == 1 and item['acceptedAnswers'][0] in item['options'], key + ': MC key')
        elif item['type'] == 'NUMERIC':
            require(item['acceptedAnswers'], key + ': no numeric key')
            for answer in item['acceptedAnswers']: numeric(answer)
        elif item['type'] == 'SHORT_ANSWER':
            require(item['acceptedAnswers'] and item.get('wordLimit', 0) > 0, key + ': short-answer limits')
            require(all(len(a.split()) <= item['wordLimit'] for a in item['acceptedAnswers']), key + ': key too long')
        else:
            require(not item['acceptedAnswers'] and len(item['criteria']) == 4, key + ': open criteria')
            if item['type'] == 'WRITING' and item.get('sampleAnswer'):
                require(len(item['sampleAnswer'].split()) >= (150 if item.get('chart') else 250), key + ': sample length')
            if item['type'] == 'SPEAKING':
                require(all(part in item['prompt'] for part in ['Part 1:', 'Part 2:', 'Part 3:']), key + ': incomplete Speaking set')
        if item['skillId'] == 'ielts_reading':
            require(item.get('evidence') and item['evidence'] in item['passage'], key + ': passage evidence')
        if item['skillId'] == 'ielts_listening':
            clip = listening[item['sourceId']]
            master = clip['audioAssetPath']
            require(item['audioAssetPath'] == compact.get(master, {}).get('compactAssetPath', master), key + ': audio mapping')
            require(item['transcriptSegments'] == clip['transcriptSegments'] and item['split'] == clip['split'], key + ': timings/split')
            require(item['transcript'] == ' '.join(s['text'] for s in item['transcriptSegments']), key + ': transcript')
            require(item['evidence'] in item['transcript'], key + ': transcript evidence')
            end = 0
            for segment in item['transcriptSegments']:
                require(end <= segment['startMs'] < segment['endMs'] <= clip['durationMs'], key + ': timing outside audio')
                end = segment['endMs']
        if item.get('sampleAudioAssetPath'):
            require(item['type'] == 'SPEAKING' and key in spoken, key + ': sample role')
            require(spoken[key]['transcript'] == item['sampleAnswer'], key + ': spoken sample transcript')
            require(compact[spoken[key]['assetPath']]['compactAssetPath'] == item['sampleAudioAssetPath'], key + ': spoken sample mapping')
        if item.get('chart'):
            chart = item['chart']; fields(chart, {'title','xLabel','yLabel','unit','labels','series'}, key)
            require(all(chart[k] for k in ['title', 'xLabel', 'yLabel', 'unit', 'labels', 'series']), key + ': chart metadata')
            for series in chart['series']:
                fields(series, {'name', 'values'}, key)
                require(len(series['values']) == len(chart['labels']) and all(isinstance(v, (int, float)) and math.isfinite(v) for v in series['values']), key + ': chart values')
    for field, grouping in groups.items(): require(all(len(s) == 1 for s in grouping.values()), field + ': split leakage')
    for skill in skills:
        subset = [e for e in items if e['skillId'] == skill]
        require({e['split'] for e in subset} == SPLITS, skill + ': missing split')
        if skills[skill]['exam'] == 'SAT':
            require(sum(e['split'] == 'DIAGNOSTIC' for e in subset) == 2, skill + ': diagnostic count')
            require(Counter(e['difficulty'] for e in subset) == {1: 12, 2: 12, 3: 12}, skill + ': difficulty quantities')
    for skill in ['ielts_reading', 'ielts_listening']:
        counts = Counter(e['sourceId'] for e in items if e['skillId'] == skill)
        require(len(counts) == 24 and set(counts.values()) == {10}, skill + ':24 sources x10 questions required')
    writing = [e for e in items if e['type'] == 'WRITING']
    require(sum(bool(e.get('chart')) for e in writing) == 12 and len(writing) == 24, 'Task1/Task2 count')
    require(sum(bool(e.get('sampleAnswer')) for e in writing) == 12, '12 written samples')
    require(sum(bool(e.get('sampleAudioAssetPath')) for e in items) == 6, '6 spoken samples')
    paths = {e[field] for e in items for field in ['audioAssetPath', 'sampleAudioAssetPath'] if e.get(field)}
    media_hashes = {value['compactAssetPath']: value['sha256'] for value in compact.values()}
    media_hashes.update({c['audioAssetPath']: c['sha256'] for c in read(ROOT / 'docs/content/audio-manifest.json')['clips'].values()})
    for relative in paths:
        media = ASSETS / relative
        require(relative.startswith('audio/') and '..' not in Path(relative).parts and media.is_file(), 'Missing/unsafe audio: ' + relative)
        require(sha(media) == media_hashes[relative], 'Media hash mismatch: ' + relative)
    original = read(ROOT / 'tools/content/pilot/seed-v2.json')
    for before in original['exercises']:
        after = by_id[before['id']]
        require(before['sourceId'] == after['sourceId'] and before['familyId'] == after['familyId'] and before['split'] == after['split'], 'Published split changed')
        require(before == after or after['version'] > before['version'], 'Changed published item needs version increment')
        require(set(before['acceptedAnswers']) <= set(after['acceptedAnswers']), 'Previously published accepted answer removed')
    previous = read(ROOT / 'tools/content/pilot/full-bank-v3.json')
    changed = []
    for before in previous['exercises']:
        after = by_id[before['id']]
        if before != after:
            changed.append(before['id'])
            require(after['version'] > before['version'], 'Changed package3 item must increment its version')
            require(before['acceptedAnswers'] == after['acceptedAnswers'], 'Package4 does not change mathematical keys')
    require(changed == ['sat-data-v3-probability-without-replacement'], 'Unexpected changes relative to frozen package3')
    return {'schemaVersion': 1, 'contentVersion': pack['version'], 'sha256': sha(path), 'reviewStatus': pack['reviewStatus'],
        'counts': {'exercises': len(items), 'lessons': len(pack['lessons']), 'sat': 288, 'readingSources': 24,
            'readingQuestions': 240, 'listeningSources': 24, 'listeningQuestions': 240, 'writingTask1': 12,
            'writingTask2': 12, 'speakingSets': 18, 'writtenSamples': 12, 'spokenAudioSamples': 6, 'bundledAudioFiles': len(paths)},
        'bySkill': {skill: dict(Counter(e['split'] for e in items if e['skillId'] == skill)) for skill in skills},
        'preservedPilotValidationSha256': pilot['sha256'], 'machineChecksPassed': ['Strict structure and enums',
            'Requested quantities, domains, levels and split coverage', 'Bilingual staged help and lesson content',
            'Evidence excerpts, transcript alignment, timestamp bounds, response limits', 'Media provenance and SHA256',
            'Published identity/version/key preservation', 'Source/family/normalized passage/audio split isolation'],
        'humanEditorialAcceptance': None, 'limitations': ['Independent AI solutions are recorded separately and do not certify human expertise.',
            'Exact source checks do not prove semantic independence.', 'Synthetic speech and drafted sample annotations require human review.',
            'Student pilot and calibrated model acceptance remain pending.']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, default=ASSETS / 'content/seed-v1.json')
    parser.add_argument('--report', type=Path, default=ROOT / 'docs/content/full-bank-validation.json')
    args = parser.parse_args()
    report = validate(args.pack)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': 'PASS', 'counts': report['counts'], 'sha256': report['sha256']}, ensure_ascii=False))
