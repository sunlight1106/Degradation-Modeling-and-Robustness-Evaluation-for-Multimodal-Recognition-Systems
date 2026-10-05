"""Build the pinned, offline ECDICT selection. Run with a local ecdict.csv path.

No download at application startup. Keep v17.json.gz immutable after release;
future catalog revisions must ship as a new database migration.
"""
import csv
import gzip
import hashlib
import json
import re
import sys
from pathlib import Path

SOURCE_COMMIT = 'bc015ed2e24a7abef49fc6dbbb7fe32c1dadaf8b'
SOURCE_SHA256 = '1a6947e04785db63613a92e14903cdae7954f7e84860b10e68e5c7cbb3f9c3cf'
ROOT = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1])
if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
    raise SystemExit('Source does not match the pinned ECDICT snapshot')

def rank(row):
    values = [int(row[k]) for k in ('frq', 'bnc') if row[k].isdigit() and int(row[k]) > 0]
    return (min(values) if values else 999999, row['word'].lower())

def chinese(text):
    return set(re.findall(r'[\u4e00-\u9fff]', text))

rows = {}
for row in csv.DictReader(source.open(encoding='utf-8')):
    term = row['word'].strip()
    if not re.fullmatch(r"[A-Za-z][A-Za-z '\-]{0,79}", term):
        continue
    lines = row['translation'].replace('\\n', '\n').splitlines()
    # Prefer a complete lexical sense, not a scraped web/dictionary cross-reference.
    lines = [s.strip() for s in lines if chinese(s) and not s.lstrip().startswith('[')]
    meaning = next((s for s in lines if len(s) <= 160), '')
    if not meaning or any(ord(c) < 32 for c in meaning) or len(row['phonetic']) > 116:
        continue
    key = term.lower()
    row['word'] = key
    row['meaning'] = meaning
    row['senses'] = chinese(' '.join(lines))
    match = re.match(r'^(n|v|vt|vi|a|adj|ad|adv|prep|pron|conj|num|interj)\.', meaning)
    row['part'] = {'a': 'adj', 'ad': 'adv', 'vt': 'v', 'vi': 'v'}.get(match[1], match[1]) + '.' if match else ''
    # Lowercase headwords win over proper-name duplicates.
    if key not in rows or term == key:
        rows[key] = row

ordered = sorted(rows.values(), key=rank)
books = []
def add(slug, title, level, description, entries):
    entries = list(entries)
    assert len(entries) >= 4
    books.append(dict(id='vocab-ec-' + slug, title=title, level=level,
                      description=description, terms=[r['word'] for r in entries]))

def tagged(tag):
    return [r for r in ordered if tag in r['tag'].split()]

descriptions = {
    'ky': '覆盖阅读、翻译与写作中的常见词汇，按通用词频渐进学习。适合系统备考与长期积累。',
    'cet4': '从校园生活到社会话题，建立大学英语阅读和听力的基础词汇。每天一小组，稳步积累。',
    'cet6': '拓展抽象概念、社会议题与书面表达。适合已有四级基础、希望提升阅读深度的学习者。',
    'ielts': '围绕留学学习与社会生活扩充词汇，适合搭配听力、阅读及写作练习使用。',
    'toefl': '积累校园与学术场景词汇，为讲座听力、阅读和综合写作打好词汇基础。',
    'gre': '扩展进阶阅读与抽象表达词汇，适合在基础词汇较扎实时逐步学习。',
    'zk': '巩固初中阶段的常见词汇，从日常表达逐步过渡到短文阅读。可按自己的节奏复习。',
    'gk': '覆盖高中学习常见词汇，适合配合教材、阅读与写作积累使用。各版本教材均可作为补充练习。',
}
for tag, title, level in [
    ('ky', '考研英语 · 综合词汇', '考研'), ('cet4', '大学英语四级', '四级'),
    ('cet6', '大学英语六级', '六级'), ('ielts', '雅思 IELTS 词汇', '雅思'),
    ('toefl', '托福 TOEFL 词汇', '托福'), ('gre', 'GRE 进阶词汇', 'GRE'),
    ('zk', '初中英语 · 中考词汇', '初中'), ('gk', '高中英语 · 高考词汇', '高中'),
]:
    add(tag, title, level, descriptions[tag], tagged(tag))
add('ky-core', '考研英语 · 高频 1500', '考研', '从考研标签词条中，按通用语料词频选出前 1500 词。适合先打基础；这里的高频不是历年试题频次。', tagged('ky')[:1500])
add('primary', '小学衔接 · 常用 500', '小学衔接', '从初中标签词条中选取常见 500 词，适合小学高年级向初中过渡；不对应某一出版社的课本单元。', tagged('zk')[:500])
add('gk-core', '高中英语 · 基础 1000', '高中', '高考标签中的常用 1000 词。先建立阅读基础，再转入高中综合词书。', tagged('gk')[:1000])
add('academic', '学术阅读 · 进阶 1500', '学术英语', '选取六级或托福标签中、基础高中词表之外的常见词，帮助积累论文与学术阅读词汇。', [r for r in ordered if set(r['tag'].split()) & {'cet6', 'toefl'} and 'gk' not in r['tag'].split()][:1500])

# Topic membership is independently curated; definitions and IPA retain ECDICT provenance.
topics = {
    'computing': ('计算机英语 · 核心术语', '计算机', '''algorithm application array argument architecture authentication authorization backup binary bit boolean branch browser buffer byte cache callback character class client cloud cluster code column command compiler component compression computer concurrency configuration connection constant constructor container context cookie core database dataset debug declaration dependency deployment desktop developer device dictionary directory disk display document domain download driver element encoding encryption endpoint engine entity error event exception execution expression extension factory field file filter firewall flag floating folder format framework function gateway generic graph hardware heap host identifier image implementation index inheritance initialization input instance instruction integer interface interpreter iteration key kernel keyword language layer library license link list load local lock log loop machine map matrix memory message method migration model module monitor network node object operator optimization output override package packet parameter parser password path pattern permission pointer pool port process processor program protocol query queue random record recursion reference registry request resource response return route runtime sample schema scope script search security sequence server session shell signature socket software sort source stack statement static storage stream string structure syntax system table task template terminal test thread timeout token transaction tree type unicode upload validation value variable vector version virtual void volume warning website window worker'''),
    'business': ('商务英语 · 职场沟通', '商务', '''account accountant accounting acquisition agenda agreement allowance appointment approve asset audit balance bank benefit bill board bonus brand budget business buyer campaign candidate capital career cash certificate chairman charge client colleague commission committee company competition competitor complaint conference confirmation conflict consultant consumer contact contract contribution cooperation corporation cost credit currency customer deadline debt decision delivery demand department deposit director discount distribution dividend document duty earnings economy efficiency employee employer employment enterprise estimate evaluation expense export factory fee finance forecast fund goods guarantee income industry inflation insurance interest interview inventory investment invoice leader leadership lease legal liability loan logistics loss management manager manufacture margin market marketing meeting merchant merger negotiation objective occupation offer office operation order organization overtime partner partnership payment pension performance plan policy portfolio position presentation price priority product production profit project promotion proposal purchase quality quotation receipt recruitment refund regulation relationship report requirement research resignation resource responsibility retail revenue review reward risk salary sale schedule secretary service shareholder shipment signature staff statement stock strategy subsidy supplier supply target tax team tender trade training transfer transport turnover vacancy wage warehouse warranty wholesale workforce workshop'''),
}
for slug, (title, level, terms) in topics.items():
    selected = [rows[term] for term in sorted(set(terms.split())) if term in rows]
    add(slug, title, level, '按学习场景人工选词，配合 ECDICT 释义与音标。可结合自己的笔记收藏和复习。', selected)

used = sorted({t for b in books for t in b['terms']})
entries = []
for term in used:
    row = rows[term]
    # Stable choices with no shared Chinese characters across any supplied senses.
    # This conservative filter avoids obvious overlapping glosses; no examples are invented.
    start = int(hashlib.sha256(term.encode()).hexdigest()[:8], 16) % len(used)
    choices, used_chars = [], set(row['senses'])
    for offset in range(len(used)):
        candidate = rows[used[(start + offset) % len(used)]]
        if candidate['word'] == term or candidate['part'] != row['part'] or len(candidate['meaning']) > 70:
            continue
        if not candidate['senses'] or used_chars & candidate['senses']:
            continue
        choices.append(candidate['meaning'])
        used_chars |= candidate['senses']
        if len(choices) == 3:
            break
    if len(choices) != 3:
        # Some function words have a rare part of speech. Different POS is preferable to an ambiguous meaning.
        for candidate_term in used:
            candidate = rows[candidate_term]
            if candidate['senses'] and not used_chars & candidate['senses'] and len(candidate['meaning']) <= 70:
                choices.append(candidate['meaning']); used_chars |= candidate['senses']
                if len(choices) == 3: break
    assert len(choices) == 3, term
    ipa = row['phonetic'].strip()
    entries.append(dict(term=term, ipa=f'/{ipa}/' if ipa else '', pos=row['part'], meaning=row['meaning'], distractors=choices))

index = {term: i for i, term in enumerate(used)}
for book in books:
    book['words'] = [index[t] for t in book.pop('terms')]
data = dict(sourceCommit=SOURCE_COMMIT, sourceSha256=SOURCE_SHA256, books=books, entries=entries)
output = ROOT / 'database/vocabulary'
output.mkdir(parents=True, exist_ok=True)
payload = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
compressed = gzip.compress(payload, mtime=0)
(output / 'v17.json.gz').write_bytes(compressed)
manifest = dict(source='https://github.com/skywind3000/ECDICT', commit=SOURCE_COMMIT,
                sourceSha256=SOURCE_SHA256, catalogSha256=hashlib.sha256(compressed).hexdigest(),
                uniqueWords=len(entries), totalEntries=sum(len(b['words']) for b in books),
                books=[{k: b[k] for k in ('id', 'title', 'level')} | {'words': len(b['words'])} for b in books])
(output / 'catalog.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=True))
