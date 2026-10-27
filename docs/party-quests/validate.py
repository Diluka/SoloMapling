#!/usr/bin/env python3
"""Validate the design catalogue against the pinned source; never starts game services."""
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[1]
ENGINE = REPOSITORY / 'upstream/BeiDou-Server'


def validate():
    catalog = json.loads((HERE / 'catalog.json').read_text())
    revision = subprocess.check_output(['git', '-C', str(ENGINE), 'rev-parse', 'HEAD'], text=True).strip()
    assert revision == catalog['source']['beidouRevision'], '北斗版本变更后需要重新核对任务规则。'
    keys = set()
    for directory in ['scripts/event', 'scripts-zh-CN/event']:
        keys.update(path.stem for path in (ENGINE / 'gms-server' / directory).glob('*.js'))
    assert set(catalog['eventIndex']) == keys, '事件目录与全量分类索引不一致。'
    assert not catalog['coverage']['unclassified'] and not catalog['coverage']['duplicateAssignments']
    covered = {}
    profiles = set()
    anchors = set()
    families = catalog['families']
    assert len({row['id'] for row in families}) == len(families), '任务家族ID重复。'
    for row in families:
        assert row['ruleProfile'] not in profiles, '同家族变体应去重，独立家族规则名不能碰撞。'
        profiles.add(row['ruleProfile'])
        assert row['cooperationRoles'] and row['cooperationModel'], '缺少团队分工或竞技隔离契约。'
        assert row['stagePlan'] or row['dedicatedRules'], row['id'] + '缺少任务阶段或专用规则。'
        assert row['status']['implementation'] == 'NOT_IMPLEMENTED', '本目录是设计交付，运行状态需要独立证据。'
        assert not row['status']['engineIntegrationVerified'] and not row['status']['clientClearVerified']
        assert row['implementationIssue'].startswith('https://github.com/Diluka/SoloMapling/issues/')
    for kind, rows in [('profile', families), ('excluded', catalog['excludedEventFamilies'])]:
        for row in rows:
            assert row['sources'], row['id'] + '缺少源码依据。'
            for key in row['eventKeys']:
                assert key not in covered, '同一事件被重复分类：' + key
                covered[key] = {'kind': kind, 'id': row['id']}
            anchors.update(row['sources'])
    assert covered == catalog['eventIndex']
    for anchor in anchors:
        match = re.fullmatch(r'(.+?)(?::(\d+))?', anchor)
        path = REPOSITORY / match[1]
        assert path.is_file(), '源码依据不存在：' + anchor
        if match[2]:
            assert 0 < int(match[2]) <= len(path.read_text(errors='replace').splitlines()), '行号超出文件：' + anchor
    for path in HERE.glob('*.md'):
        for target in re.findall(r'\]\(([^)]+)\)', path.read_text()):
            if re.match(r'\w+://', target) or target.startswith('#'):
                continue
            target = target.split('#', 1)[0]
            assert (path.parent / target).exists(), f'{path.name}中的本地链接不存在：{target}'
    print(f'设计目录验证通过：{len(keys)}个事件key全部唯一分类，{len(families)}个规则家族，{len(anchors)}处唯一源码引用。')
    print('仅验证设计结构、来源路径和范围覆盖；没有实现或运行任何组队任务。')


if __name__ == '__main__':
    validate()
