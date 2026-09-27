#!/usr/bin/env python3
"""Full-bank structural and provenance checks. These do not constitute expert acceptance."""
from __future__ import annotations
from collections import Counter, defaultdict
from pathlib import Path
import argparse
import hashlib
import json
import math
import re
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


def canonical_sha(item):
    return hashlib.sha256(json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


PACKAGE_VERSION = 7
SCHEMA_VERSION = 3
ORIGINAL_EXERCISES = 810
LONG_READING = {'PRACTICE': 2, 'ASSESSMENT': 3}
NEW_TASK1 = {'LINE': 1, 'PIE': 1, 'TABLE': 2, 'PROCESS': 1, 'MAP': 2}
FORMATS = {'TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MATCHING_HEADINGS', 'MATCHING_INFORMATION', 'MATCHING_FEATURES',
    'SUMMARY_COMPLETION', 'SENTENCE_COMPLETION', 'NOTE_COMPLETION', 'TABLE_COMPLETION', 'DIAGRAM_LABEL', 'MAP_LABEL',
    'MULTIPLE_CHOICE', 'SHORT_ANSWER'}
CHECK_KINDS = {'TASK_PART', 'VIEW', 'POSITION', 'SUPPORT', 'OVERVIEW', 'COMPARISON', 'DATA', 'ACCURACY'}
LIST_FORMATS = {'MATCHING_HEADINGS', 'MATCHING_INFORMATION', 'MATCHING_FEATURES', 'SUMMARY_COMPLETION', 'MAP_LABEL'}
GAP = re.compile(r'\[\[([^\]]+)\]\]')
MAX_ASR_WER = 0.12


def figure_refs(figure, where):
    """Validate figure geometry; return gap markers used in node labels."""
    fields(figure, {'kind', 'title', 'panels', 'caption'}, where)
    require(figure['kind'] in {'PROCESS', 'MAP', 'DIAGRAM'} and figure['title'].strip() and figure['panels'], where + ': figure')
    refs = []
    for panel in figure['panels']:
        fields(panel, {'title', 'nodes', 'links'}, where)
        ids = set()
        for node in panel['nodes']:
            fields(node, {'id', 'label', 'x', 'y', 'width', 'height', 'shape'}, where)
            width, height = node.get('width', 0.24), node.get('height', 0.14)
            require(node['label'].strip() and node['id'] not in ids and node.get('shape', 'BOX') in {'BOX', 'ROUND', 'LABEL'}, where + ': node')
            require(0 <= node['x'] and 0 <= node['y'] and width > 0 and height > 0 and node['x'] + width <= 1.0001 and node['y'] + height <= 1.0001, where + ': node outside panel')
            ids.add(node['id']); refs += GAP.findall(node['label'])
        for link in panel.get('links', []):
            fields(link, {'from', 'to', 'label'}, where)
            require(link['from'] in ids and link['to'] in ids and link['from'] != link['to'], where + ': link')
    return refs


def validate(path):
    pilot = validate_pilot(ROOT / 'tools/content/pilot/seed-v2.json')
    pack = read(path)
    if path.resolve() == (ASSETS / 'content/seed-v1.json').resolve():
        info = read(ASSETS / 'content/bundle-info.json')
        require(info == {'schemaVersion': 1, 'contentId': pack['id'], 'contentVersion': pack['version'],
            'contentSchemaVersion': pack['schemaVersion'], 'sha256': sha(path)}, 'Bundled version/hash metadata mismatch')
    fields(pack, PACK_FIELDS, 'pack')
    require(pack['schemaVersion'] == SCHEMA_VERSION and pack['version'] == PACKAGE_VERSION, f'Expected full bank schema{SCHEMA_VERSION} / package{PACKAGE_VERSION}')
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
    frozen = read(ROOT / 'tools/content/pilot/package4-index.json')
    original_ids = [e['id'] for e in frozen['exercises']]
    require(len(original_ids) == ORIGINAL_EXERCISES and [e['id'] for e in items[:ORIGINAL_EXERCISES]] == original_ids, 'Package4 exercises must stay first, in order')
    additions = items[ORIGINAL_EXERCISES:]
    long_reading = [e for e in additions if e['skillId'] == 'ielts_reading']
    new_task1 = [e for e in additions if e['skillId'] == 'ielts_writing']
    require(len(additions) == len(long_reading) + len(new_task1), 'Only long Reading passages and Task 1 visuals are added after package4')
    require(len(by_id) == len(items), 'Exercise ids must be unique')
    require(Counter(e['skillId'] for e in items[:ORIGINAL_EXERCISES]) == {
        **{key: 36 for key, s in skills.items() if s['exam'] == 'SAT'},
        'ielts_reading': 240, 'ielts_listening': 240, 'ielts_writing': 24, 'ielts_speaking': 18}, 'Full-bank quantities')
    listening = read(ROOT / 'tools/content/expansion/listening_work/audio-manifest.json')['clips']
    compact = read(ROOT / 'docs/content/compact-audio-manifest.json')['assets']
    spoken = read(ROOT / 'docs/content/speaking-audio-manifest.json')['samples']
    # Package7: every Listening recording and spoken sample uses the multi-voice Piper audio described here.
    voices = read(ROOT / 'docs/content/listening-voices-manifest.json')
    require(voices['synthetic'] is True and voices['reviewStatus'].endswith('REVIEW_PENDING'), 'Synthetic voices must stay marked as unreviewed')
    require(all(not v['distributed'] and len(v['modelSha256']) == 64 and v['modelCard'].startswith('https://') for v in voices['voices'].values()), 'Voice provenance')
    for name, media in [*voices['clips'].items(), *voices['samples'].items()]:
        require(media['asr']['newWer'] <= MAX_ASR_WER, name + ': machine transcription check')
        require(abs(media['decodedDurationMs'] - media['durationMs']) < 100, name + ': decoded duration')
    groups = {key: defaultdict(set) for key in ['familyId', 'sourceId', 'passage', 'audioAssetPath']}
    for item in items:
        key = item['id']; fields(item, EX_FIELDS | {'sampleAudioAssetPath', 'format', 'group', 'figure', 'sourceTitle', 'taskChecklist'}, key)
        if item['type'] == 'WRITING':
            # Package6: every Writing task carries its own self-check list; nothing in it is a score.
            checks = item.get('taskChecklist') or []
            require(4 <= len(checks) <= 6 and len({c['id'] for c in checks}) == len(checks), key + ': task checklist size')
            for check in checks:
                fields(check, {'id', 'kind', 'text'}, key); bilingual(check['text'], key + '.taskChecklist')
                require(check['kind'] in CHECK_KINDS and check['text']['en'].rstrip().endswith('?'), key + ': check kind/question')
                require(not re.search(r'\b(band|score)\b', check['text']['en'], re.I), key + ': a check never names a band or score')
            kinds = {c['kind'] for c in checks}
            if item.get('chart') or item.get('figure'):
                require({'OVERVIEW', 'COMPARISON', 'DATA', 'ACCURACY'} <= kinds, key + ': Task 1 checks')
            else:
                require('SUPPORT' in kinds and kinds & {'POSITION', 'TASK_PART'}, key + ': Task 2 checks')
        else:
            require('taskChecklist' not in item, key + ': checklists belong to Writing')
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
        if item.get('format') is not None: require(item['format'] in FORMATS, key + ': format')
        if item['type'] == 'MULTIPLE_CHOICE':
            listed = item.get('format') in LIST_FORMATS and bool(item.get('group', {}).get('options'))
            require(len(item['options']) == len(set(item['options'])) and (len(item['options']) == 4 if item['exam'] == 'SAT' else (3 <= len(item['options']) <= 12 if listed else 3 <= len(item['options']) <= 4)), key + ': option uniqueness')
            if listed: require(item['options'] == [o['key'] for o in item['group']['options']], key + ': list keys')
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
            clip = voices['clips'][item['sourceId']]
            previous = listening[item['sourceId']]
            require(item['audioAssetPath'] == clip['compactAssetPath'] and item['split'] == clip['split'] == previous['split'], key + ': audio mapping/split')
            require(item['transcriptSegments'] == clip['segments'], key + ': timings')
            require(item['transcript'] == ' '.join(s['text'] for s in item['transcriptSegments']), key + ': transcript')
            # Re-voicing never edits the script: the same sentences, in the same order, as the published recording.
            require([s['text'] for s in item['transcriptSegments']] == [s['text'] for s in previous['transcriptSegments']], key + ': script changed')
            require(hashlib.sha256(item['transcript'].encode()).hexdigest() == clip['transcriptSha256'], key + ': transcript hash')
            require(item['evidence'] in item['transcript'], key + ': transcript evidence')
            labels = {s['label'] for s in clip['speakers']}
            end = 0
            for segment in item['transcriptSegments']:
                fields(segment, {'startMs', 'endMs', 'text', 'speaker'}, key)
                require(end <= segment['startMs'] < segment['endMs'] <= clip['decodedDurationMs'], key + ': timing outside audio')
                require(segment.get('speaker') in labels, key + ': every segment names its speaker')
                end = segment['endMs']
        if item.get('sampleAudioAssetPath'):
            require(item['type'] == 'SPEAKING' and key in spoken and key in voices['samples'], key + ': sample role')
            require(spoken[key]['transcript'] == item['sampleAnswer'], key + ': spoken sample transcript')
            require(hashlib.sha256(item['sampleAnswer'].encode()).hexdigest() == voices['samples'][key]['transcriptSha256'], key + ': spoken sample hash')
            require(voices['samples'][key]['compactAssetPath'] == item['sampleAudioAssetPath'], key + ': spoken sample mapping')
        if item.get('chart'):
            chart = item['chart']; fields(chart, {'title','xLabel','yLabel','unit','labels','series','kind'}, key)
            require(all(chart[k] for k in ['title', 'xLabel', 'yLabel', 'unit', 'labels', 'series']), key + ': chart metadata')
            require(chart.get('kind', 'BAR') in {'BAR', 'LINE', 'PIE', 'TABLE'}, key + ': chart kind')
            for series in chart['series']:
                fields(series, {'name', 'values'}, key)
                require(len(series['values']) == len(chart['labels']) and all(isinstance(v, (int, float)) and math.isfinite(v) for v in series['values']), key + ': chart values')
                if chart.get('kind') == 'PIE': require(all(v >= 0 for v in series['values']) and abs(sum(series['values']) - 100) < 0.01, key + ': pie shares')
        if item.get('figure'):
            require(item['type'] == 'WRITING' and not item.get('chart') and not figure_refs(item['figure'], key), key + ': Task 1 figure')
        if item.get('group'):
            group = item['group']; fields(group, {'id', 'instruction', 'options', 'text', 'figure'}, key)
            require(group['id'].startswith(item['sourceId'] + ':') and group['instruction'].strip(), key + ': group identity')
            for option in group.get('options', []):
                fields(option, {'key', 'text'}, key); require(option['key'].strip() and option['text'].strip(), key + ': group option')
            require(len({o['key'] for o in group.get('options', [])}) == len(group.get('options', [])), key + ': group keys')
    for field, grouping in groups.items(): require(all(len(s) == 1 for s in grouping.values()), field + ': split leakage')
    members = defaultdict(list)
    for position, item in enumerate(items):
        if item.get('group'): members[item['group']['id']].append((position, item))
    for group_id, entries in members.items():
        first = entries[0][1]['group']
        require(all(item['group'] == first for _, item in entries), group_id + ': group context differs')
        require([p for p, _ in entries] == list(range(entries[0][0], entries[0][0] + len(entries))), group_id + ': members must be consecutive')
        ids = {item['id'] for _, item in entries}
        refs = GAP.findall(first.get('text') or '') + (figure_refs(first['figure'], group_id) if first.get('figure') else [])
        require(set(refs) <= ids and len(refs) == len(set(refs)), group_id + ': gap markers')
    for skill in skills:
        subset = [e for e in items if e['skillId'] == skill]
        require({e['split'] for e in subset} == SPLITS, skill + ': missing split')
        if skills[skill]['exam'] == 'SAT':
            require(sum(e['split'] == 'DIAGNOSTIC' for e in subset) == 2, skill + ': diagnostic count')
            require(Counter(e['difficulty'] for e in subset) == {1: 12, 2: 12, 3: 12}, skill + ': difficulty quantities')
    for skill in ['ielts_reading', 'ielts_listening']:
        counts = Counter(e['sourceId'] for e in items[:ORIGINAL_EXERCISES] if e['skillId'] == skill)
        require(len(counts) == 24 and set(counts.values()) == {10}, skill + ':24 sources x10 questions required')
    # Package5: full-length Academic passages in the formats the short passages lacked.
    long_sources = defaultdict(list)
    for item in long_reading: long_sources[item['sourceId']].append(item)
    require(Counter(v[0]['split'] for v in long_sources.values()) == LONG_READING, 'Long passage splits')
    for source, questions in long_sources.items():
        passage = questions[0]['passage']
        require(source.startswith('reading-v5-') and all(q['passage'] == passage and q['familyId'] == source for q in questions), source + ': one passage')
        require(700 <= len(passage.split()) <= 900 and 13 <= len(questions) <= 14, source + ': 700-900 words and 13-14 questions')
        require(all(q.get('format') in FORMATS and q.get('sourceTitle') for q in questions), source + ': formats and title')
    assessment_questions = sum(len(v) for v in long_sources.values() if v[0]['split'] == 'ASSESSMENT')
    require(assessment_questions == 40, 'Three assessment passages provide one 40-question reading paper')
    for fmt in ['TRUE_FALSE_NOT_GIVEN', 'YES_NO_NOT_GIVEN', 'MATCHING_HEADINGS', 'MATCHING_INFORMATION', 'MATCHING_FEATURES', 'SUMMARY_COMPLETION', 'DIAGRAM_LABEL']:
        require(any(q.get('format') == fmt for q in long_reading), 'Missing reading format ' + fmt)
    visuals = Counter((e.get('chart') or {}).get('kind') or e['figure']['kind'] for e in new_task1)
    require(visuals == NEW_TASK1 and all(e.get('minWords') == 150 and len(e['criteria']) == 4 for e in new_task1), 'Task 1 visual coverage')
    writing = [e for e in items if e['type'] == 'WRITING']
    require(sum(bool(e.get('chart')) for e in writing[:24]) == 12 and len(writing) == 24 + len(new_task1), 'Task1/Task2 count')
    require(sum(bool(e.get('sampleAnswer')) for e in writing[:24]) == 12, '12 written samples')
    require(sum(bool(e.get('sampleAudioAssetPath')) for e in items) == 6, '6 spoken samples')
    paths = {e[field] for e in items for field in ['audioAssetPath', 'sampleAudioAssetPath'] if e.get(field)}
    require(len(paths) == 30 and all('-voices/' in p for p in paths), 'Every current recording uses the Piper voices')
    # Exam mode plays only reserved assessment recordings: they must include a conversation and more than one accent.
    exam_clips = [c for c in voices['clips'].values() if c['split'] == 'ASSESSMENT']
    require(any(len(c['speakers']) > 1 for c in exam_clips), 'Assessment Listening needs a multi-speaker recording')
    exam_accents = {s['accent'] for c in exam_clips for s in c['speakers']}
    require(len(exam_accents) > 1, 'Assessment Listening needs more than one accent')
    media_hashes = {value['compactAssetPath']: value['sha256'] for value in compact.values()}
    media_hashes.update({c['audioAssetPath']: c['sha256'] for c in read(ROOT / 'docs/content/audio-manifest.json')['clips'].values()})
    # Earlier exercise versions keep playing their original eSpeak files, so those stay bundled and unchanged.
    for relative, digest in list(media_hashes.items()):
        require((ASSETS / relative).is_file() and sha(ASSETS / relative) == digest, 'Earlier-version audio missing or changed: ' + relative)
    media_hashes.update({m['compactAssetPath']: m['sha256'] for m in [*voices['clips'].values(), *voices['samples'].values()]})
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
    for before in previous['exercises']:
        after = by_id[before['id']]
        if before != after:
            require(after['version'] > before['version'], 'Changed package3 item must increment its version')
            require(set(before['acceptedAnswers']) <= set(after['acceptedAnswers']), 'Previously published accepted answer removed')
    # Package4 is frozen as a per-exercise hash index: every change needs a newer exercise version.
    require(frozen['contentVersion'] == 4, 'Package4 index')
    changed = []
    for before in frozen['exercises']:
        after = by_id[before['id']]
        require((after['split'], after['familyId'], after['sourceId']) == (before['split'], before['familyId'], before['sourceId']), 'Published split changed')
        require(set(before['acceptedAnswers']) <= set(after['acceptedAnswers']), 'Previously published accepted answer removed')
        if canonical_sha(after) != before['sha256']:
            changed.append(before['id'])
            require(after['version'] > before['version'], 'Changed package4 item must increment its version: ' + before['id'])
    # Package5 was built on this branch; any later change to one of its items also needs a newer exercise version.
    package5 = read(ROOT / 'tools/content/pilot/package5-index.json')
    require(package5['contentVersion'] == 5 and len(package5['exercises']) == ORIGINAL_EXERCISES + len(additions), 'Package5 index')
    changed5 = []
    for before in package5['exercises']:
        after = by_id[before['id']]
        if canonical_sha(after) != before['sha256']:
            changed5.append(before['id'])
            require(after['version'] > before['version'], 'Changed package5 item must increment its version: ' + before['id'])
    package6 = read(ROOT / 'tools/content/pilot/package6-index.json')
    require(package6['contentVersion'] == 6 and [e['id'] for e in package6['exercises']] == [e['id'] for e in items], 'Package6 index')
    changed6 = []
    for before in package6['exercises']:
        after = by_id[before['id']]
        if canonical_sha(after) != before['sha256']:
            changed6.append(before['id'])
            require(after['version'] > before['version'], 'Changed package6 item must increment its version: ' + before['id'])
    revoiced = {e['id'] for e in items if e['skillId'] == 'ielts_listening' or e.get('sampleAudioAssetPath')}
    require(set(changed6) == revoiced, 'Package7 changes only the re-voiced Listening items and spoken samples')
    return {'schemaVersion': 1, 'contentVersion': pack['version'], 'sha256': sha(path), 'reviewStatus': pack['reviewStatus'],
        'counts': {'exercises': len(items), 'lessons': len(pack['lessons']), 'sat': 288, 'readingSources': 24 + len(long_sources),
            'readingQuestions': 240 + len(long_reading), 'longReadingPassages': len(long_sources), 'listeningSources': 24, 'listeningQuestions': 240,
            'writingTask1': 12 + len(new_task1), 'writingTask2': 12, 'speakingSets': 18, 'writtenSamples': 12, 'spokenAudioSamples': 6,
            'bundledAudioFiles': len(paths), 'changedSincePackage4': len(changed), 'changedSincePackage5': len(changed5), 'changedSincePackage6': len(changed6),
            'multiSpeakerRecordings': sum(len(c['speakers']) > 1 for c in voices['clips'].values()),
            'recordingAccents': sorted({s['accent'] for c in voices['clips'].values() for s in c['speakers']}),
            'assessmentRecordingAccents': sorted(exam_accents),
            'writingTaskChecklists': sum(bool(e.get('taskChecklist')) for e in writing)},
        'readingFormats': dict(sorted(Counter(e.get('format', 'UNLABELLED') for e in items if e['skillId'] == 'ielts_reading').items())),
        'task1Visuals': dict(sorted(Counter((e.get('chart') or {}).get('kind', 'BAR') if e.get('chart') else e['figure']['kind'] for e in writing if e.get('chart') or e.get('figure')).items())),
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
