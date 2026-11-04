#!/usr/bin/env python3
"""Index the pinned BeiDou XML resources; this does not certify runtime AI support."""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import posixpath
import subprocess
import xml.etree.ElementTree as ET


def child(node, name):
    return next((entry for entry in node if entry.get("name") == name), None)


def scalar(node):
    if node.tag in {"int", "short", "long"}:
        return int(node.get("value"))
    if node.tag in {"float", "double"}:
        return float(node.get("value"))
    if node.tag == "vector":
        return [int(node.get("x")), int(node.get("y"))]
    return node.get("value")


def fields(node, prefix=""):
    result = {}
    for entry in node:
        name = prefix + entry.get("name")
        if entry.tag == "imgdir":
            result.update(fields(entry, name + "/"))
        elif entry.tag != "canvas":
            result[name] = scalar(entry)
    return result


def visual(root):
    """Resolve WZ UOL paths relative to their containing directory, preserving failures."""
    nodes = {}
    paths = {}

    def visit(node, path):
        nodes[path] = node
        paths[node] = path
        for entry in node:
            visit(entry, posixpath.join(path, entry.get("name")))

    visit(root, "")
    references = []

    def resolve(path):
        seen = set()
        node = nodes.get(path)
        while node is not None and node.tag == "uol":
            if path in seen:
                return None
            seen.add(path)
            path = posixpath.normpath(posixpath.join(posixpath.dirname(path), node.get("value")))
            node = nodes.get(path)
        return node

    for path, node in nodes.items():
        if node.tag == "uol":
            references.append({"path": path, "target": node.get("value"), "resolved": resolve(path) is not None})
    actions = {}
    for action in root:
        name = action.get("name")
        if name == "info":
            continue
        action_node = resolve(name)
        if action_node is None:
            actions[name] = {"unresolved": True}
            continue
        delays = []
        bounds = []
        for frame in action_node:
            if not frame.get("name").isdigit():
                continue
            resolved = resolve(paths[frame])
            if resolved is None:
                delays.append(None)
                continue
            delay = child(resolved, "delay")
            delays.append(int(delay.get("value")) if delay is not None else None)
            lt, rb = child(resolved, "lt"), child(resolved, "rb")
            if lt is not None and rb is not None:
                bounds.append([*scalar(lt), *scalar(rb)])
            else:
                origin = child(resolved, "origin")
                if origin is not None and resolved.tag == "canvas":
                    x, y = scalar(origin)
                    bounds.append([-x, -y, int(resolved.get("width")) - x, int(resolved.get("height")) - y])
        actions[name] = {
            "frames": len(delays), "delay_ms": delays,
            "explicit_duration_ms": sum(delay for delay in delays if delay is not None),
        }
        if bounds:
            actions[name]["bounds_union"] = [min(b[0] for b in bounds), min(b[1] for b in bounds),
                                              max(b[2] for b in bounds), max(b[3] for b in bounds)]
    return actions, references


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--language", default="zh-CN")
    args = parser.parse_args()
    server = args.upstream / "gms-server"
    base = server / "wz"
    localized = server / ("wz-" + args.language)
    files = {int(path.name.split(".")[0]): path for path in (base / "Mob.wz").glob("*.img.xml")}
    localized_files = {int(path.name.split(".")[0]): path for path in (localized / "Mob.wz").glob("*.img.xml")}
    # LocalizedDataProvider chooses a whole translated XML, then falls back to the base XML.
    effective = files | localized_files
    roots = {mid: ET.parse(path).getroot() for mid, path in effective.items()}
    infos = {mid: fields(child(root, "info")) for mid, root in roots.items()}
    visuals = {mid: visual(root) for mid, root in roots.items()}
    skill_path = base / "Skill.wz/MobSkill.img.xml"
    if (localized / "Skill.wz/MobSkill.img.xml").exists():
        skill_path = localized / "Skill.wz/MobSkill.img.xml"
    skills = []
    for skill in ET.parse(skill_path).getroot():
        for level in child(skill, "level"):
            values = fields(level)
            summons = [int(value) for name, value in values.items() if name.isdigit()]
            skills.append({"id": int(skill.get("name")), "level": int(level.get("name")), "fields": values,
                           "summon_ids": summons, "missing_summon_definitions": [mid for mid in summons if mid not in roots]})
    skill_ids = {(skill["id"], skill["level"]) for skill in skills}
    spawns = defaultdict(set)
    map_files = sorted((base / "Map.wz/Map").rglob("*.img.xml"))
    for path in map_files:
        relative = path.relative_to(base)
        translated = localized / relative
        root = ET.parse(translated if translated.exists() else path).getroot()
        life = child(root, "life")
        if life is None:
            continue
        for entry in life:
            values = fields(entry)
            if values.get("type") == "m":
                spawns[int(values["id"])].add(int(path.name.split(".")[0]))
    records = []
    families = Counter()
    for mid in sorted(effective):
        chain, current = [], mid
        while current in infos and current not in chain:
            chain.append(current)
            linked = infos[current].get("link")
            if not linked:
                break
            current = int(linked)
        status = "missing" if current not in infos else "cycle" if infos[current].get("link") and int(infos[current]["link"]) in chain else "resolved"
        # Keep raw and linked visual ownership explicit; do not merge linked combat statistics.
        visual_mid = next((entry for entry in chain if any(
            (name in {"stand", "fly"} and action.get("frames", 0))
            or (name.startswith(("stand", "move", "fly", "jump", "attack", "hit")) and action.get("bounds_union"))
            for name, action in visuals[entry][0].items())), mid)
        actions = visuals[visual_mid][0]
        refs = visuals[mid][1]
        info = infos[mid]
        groups = []
        if "fly" in actions:
            groups.append("flying")
        if "move" in actions:
            groups.append("ground_move")
        if "jump" in actions:
            groups.append("jump_action_present")
        if "move" not in actions and "fly" not in actions:
            groups.append("no_move_or_fly_action")
        if info.get("damagedByMob"):
            groups.append("friendly")
        if info.get("boss"):
            groups.append("boss")
        if any(key in info for key in ["selfDestruction/action", "ban/banMsg", "notAttack", "invincible", "noFlip"]):
            groups.append("special_flags")
        families.update(groups)
        missing = [name for name in ["level", "maxHP", "maxMP", "PADamage", "PDDamage", "MADamage", "MDDamage", "speed", "fs"] if name not in info]
        missing_skills = []
        for name, value in info.items():
            if name.startswith("skill/") and name.endswith("/skill"):
                level = int(info.get(name.removesuffix("skill") + "level", 0))
                if (int(value), level) not in skill_ids:
                    missing_skills.append([int(value), level])
        revives = [int(value) for name, value in info.items() if name.startswith("revive/")]
        records.append({
            "id": mid, "source": effective[mid].relative_to(args.upstream).as_posix(),
            "sha256": hashlib.sha256(effective[mid].read_bytes()).hexdigest(),
            "info": info, "info_link_chain": chain, "info_link_status": status,
            "visual_source_id": visual_mid, "families": groups, "actions": actions,
            "local_action_names": list(visuals[mid][0]),
            "uol_count": len(refs), "unresolved_uol": [ref for ref in refs if not ref["resolved"]],
            "missing_info_fields": missing, "missing_skill_definitions": missing_skills,
            "revive_ids": revives, "missing_revive_definitions": [target for target in revives if target not in infos],
            "map_resource_ids": sorted(spawns[mid]), "runtime_support": "not_certified_by_static_index",
        })
    auxiliary = sorted(path.relative_to(args.upstream).as_posix() for path in (base / "Mob.wz").rglob("*.xml") if path.parent != base / "Mob.wz")
    summary = {
        "schema_version": 1,
        "upstream_commit": subprocess.check_output(["git", "-C", str(args.upstream), "rev-parse", "HEAD"], text=True).strip(),
        "upstream_worktree_changes": subprocess.check_output(["git", "-C", str(args.upstream), "status", "--porcelain"], text=True).splitlines(),
        "language": args.language, "base_monster_definitions": len(files),
        "localized_overrides": len(localized_files), "effective_monster_definitions": len(records),
        "auxiliary_files": auxiliary, "mob_skill_classes": len({skill["id"] for skill in skills}),
        "mob_skill_levels": len(skills), "map_resources_scanned": len(map_files),
        "mob_skill_source": skill_path.relative_to(args.upstream).as_posix(),
        "mob_skill_sha256": hashlib.sha256(skill_path.read_bytes()).hexdigest(),
        "families_nonexclusive": dict(sorted(families.items())),
        "info_link_count": sum(bool(record["info"].get("link")) for record in records),
        "unresolved_info_links": [record["id"] for record in records if record["info_link_status"] != "resolved"],
        "unresolved_uol_count": sum(len(record["unresolved_uol"]) for record in records),
        "linked_visual_motion_without_local_motion": [record["id"] for record in records
            if not {"move", "fly"}.intersection(record["local_action_names"])
            and {"move", "fly"}.intersection(record["actions"])],
        "missing_skill_definitions": {str(record["id"]): record["missing_skill_definitions"] for record in records if record["missing_skill_definitions"]},
        "missing_revive_definitions": {str(record["id"]): record["missing_revive_definitions"] for record in records if record["missing_revive_definitions"]},
        "missing_skill_summon_definitions": [skill for skill in skills if skill["missing_summon_definitions"]],
        "map_resource_monster_ids_without_definition": sorted(mid for mid in spawns if mid not in roots),
        "first_batch": [record for record in records if record["id"] in {100101, 120100, 130101, 1210100}],
        "evidence_boundary": "Static XML parsing only. Map references are resource declarations, not observed spawns. No normal client motion/AI samples were collected.",
    }
    args.output.mkdir(parents=True, exist_ok=True)
    for name, rows in [("monster-data-index.jsonl", records), ("monster-data-skills.jsonl", skills)]:
        (args.output / name).write_text("".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows))
    (args.output / "monster-data-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key not in {"first_batch", "missing_skill_definitions"}}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
