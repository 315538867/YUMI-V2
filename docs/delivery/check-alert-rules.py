#!/usr/bin/env python3
"""告警规则结构校验（无第三方依赖）：规则成组、alert 名唯一、且含 expr/for/severity/summary。
缺项会让规则静默失效，因此与「指标是否存在」同等对待。退出码 0 = 结构完整。"""
import re
import sys


def check(path: str) -> int:
    lines = open(path, encoding="utf-8").read().splitlines()
    rules: list[dict] = []
    current = None
    groups = 0
    for line in lines:
        if re.match(r"^  - name:\s*\S", line):
            groups += 1
        m = re.match(r"^\s*- alert:\s*(\S+)", line)
        if m:
            if current:
                rules.append(current)
            current = {"name": m.group(1), "expr": False, "for": False,
                       "severity": False, "summary": False}
            continue
        if current is None:
            continue
        if re.match(r"^\s*expr:", line):
            current["expr"] = True
        if re.match(r"^\s*for:", line):
            current["for"] = True
        if re.match(r"^\s*severity:", line):
            current["severity"] = True
        if re.match(r"^\s*summary:", line):
            current["summary"] = True
    if current:
        rules.append(current)

    problems = []
    if groups == 0:
        problems.append("没有任何 group")
    if not rules:
        problems.append("没有任何规则")
    for rule in rules:
        for field in ("expr", "for", "severity", "summary"):
            if not rule[field]:
                problems.append(f"{rule['name']}: 缺少 {field}")
    names = [rule["name"] for rule in rules]
    dupes = sorted({n for n in names if names.count(n) > 1})
    if dupes:
        problems.append("重复的 alert 名: " + ", ".join(dupes))

    if problems:
        print("FAIL(结构): " + "; ".join(problems))
        return 1
    print(f"ok(structure) {groups} 个分组、{len(rules)} 条规则结构完整且命名唯一")
    return 0


if __name__ == "__main__":
    sys.exit(check(sys.argv[1] if len(sys.argv) > 1 else "docs/delivery/alert-rules.yml"))
