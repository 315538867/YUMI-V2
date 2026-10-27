from pathlib import Path
import argparse
import re
import sys


def task_refs(value):
    expanded = []
    pattern = r"(\d+\.\d+)\s*[—–]\s*(\d+\.\d+)"
    for match in re.finditer(pattern, value):
        first_group, first_number = map(int, match[1].split("."))
        last_group, last_number = map(int, match[2].split("."))
        if first_group != last_group or first_number > last_number:
            raise ValueError(f"非法任务范围：{match[0]}")
        expanded.extend(f"{first_group}.{number}" for number in range(first_number, last_number + 1))
    expanded.extend(re.findall(r"\b\d+\.\d+\b", re.sub(pattern, "", value)))
    return expanded


def table_rows(text):
    return [[cell.strip() for cell in line.strip().strip("|").split("|")]
            for line in text.splitlines() if line.startswith("| ")]


def validate(root, planning_only=False):
    errors = []
    tasks_text = (root / "tasks.md").read_text()
    tasks = {}
    positions = {}
    checked = 0
    for match in re.finditer(r"^- \[([ x])\] (\d+\.\d+) (.+)$", tasks_text, re.M):
        state, identifier, body = match.groups()
        if identifier in tasks:
            errors.append(f"重复任务：{identifier}")
        positions[identifier] = len(tasks)
        tasks[identifier] = body
        checked += state == "x"
        for label in ["依赖：", "完成：", "验证："]:
            if label not in body:
                errors.append(f"任务 {identifier} 缺 {label}")
    if not tasks:
        errors.append("未找到任务")
    if planning_only and checked:
        errors.append("计划阶段不应勾选实施任务")
    for group in sorted({int(identifier.split(".")[0]) for identifier in tasks}):
        actual = sorted(int(identifier.split(".")[1]) for identifier in tasks if identifier.startswith(f"{group}."))
        if actual != list(range(1, len(actual) + 1)):
            errors.append(f"任务组 {group} 编号不连续")
    dependencies = {}
    for identifier, body in tasks.items():
        dependency = re.search(r"依赖：([^；]+)", body)
        dependencies[identifier] = task_refs(dependency[1]) if dependency else []
        for previous in dependencies[identifier]:
            if previous not in tasks or positions.get(previous, len(tasks)) >= positions[identifier]:
                errors.append(f"非前向依赖：{identifier} -> {previous}")

    def ancestors(identifier):
        found = set()
        pending = list(dependencies.get(identifier, []))
        while pending:
            previous = pending.pop()
            if previous not in found:
                found.add(previous)
                pending.extend(dependencies.get(previous, []))
        return found

    def require_refs(value, location):
        identifiers = task_refs(value)
        if not identifiers:
            errors.append(f"缺任务映射：{location}")
        for identifier in identifiers:
            if identifier not in tasks:
                errors.append(f"未知任务：{location} -> {identifier}")
        return identifiers

    requirements = {}
    scenario_count = 0
    proposal = (root / "proposal.md").read_text().split("### New Capabilities")[1].split("### Modified Capabilities")[0]
    declared = set(re.findall(r"^- `([a-z-]+)`", proposal, re.M))
    actual = {path.parent.name for path in (root / "specs").glob("*/spec.md")}
    if declared != actual:
        errors.append(f"能力声明与规范目录不一致：{sorted(declared ^ actual)}")
    for spec in sorted((root / "specs").glob("*/spec.md")):
        content = spec.read_text()
        for section in re.split(r"^### Requirement: ", content, flags=re.M)[1:]:
            title, _, body = section.partition("\n")
            identifier = title.split()[0]
            if identifier in requirements:
                errors.append(f"重复要求：{identifier}")
            requirements[identifier] = (title, str(spec.relative_to(root)))
            if "SHALL" not in body or "#### Scenario: " not in body:
                errors.append(f"要求缺 SHALL 或场景：{identifier}")
            for scenario in body.split("#### Scenario: ")[1:]:
                scenario_count += 1
                if "**WHEN**" not in scenario or "**THEN**" not in scenario:
                    errors.append(f"场景缺 WHEN/THEN：{identifier}")
    mapped = set()
    for row in table_rows((root / "coverage.md").read_text()):
        if not re.match(r"^[A-Z]+-\d+ ", row[0]):
            continue
        identifier = row[0].split()[0]
        if identifier in mapped:
            errors.append(f"重复覆盖：{identifier}")
        mapped.add(identifier)
        expected = requirements.get(identifier)
        if not expected or row[0] != expected[0] or f"({expected[1]})" not in row[1]:
            errors.append(f"要求标题或规范路径不一致：{identifier}")
        require_refs(row[2], identifier)
    if mapped != set(requirements):
        errors.append(f"要求映射缺失/多余：{sorted(mapped ^ set(requirements))}")

    delivery = (root / "delivery-contracts.md").read_text()
    contracts = set()
    returned_disposition_routes = []
    owners = {"platform", "customer", "product", "workforce", "production", "orders", "finance", "reporting"}
    for row in table_rows(delivery.split("## 3.")[0]):
        if not re.fullmatch(r"[A-Z]\d{2}", row[0]):
            continue
        if len(row) != 8:
            errors.append(f"交付清单列数错误：{row[0]}")
            continue
        identifier, owner, baseline, actions, files, backend, frontend, tests = row
        if "disposeReturnedProduct" in actions:
            returned_disposition_routes.append((identifier, owner, files.split(" / ")[0]))
        if identifier in contracts:
            errors.append(f"重复交付项：{identifier}")
        contracts.add(identifier)
        if owner.split("/")[0] not in owners or not baseline or not actions or not files:
            errors.append(f"交付项缺 owner/基线/行为/接口：{identifier}")
        providers = require_refs(backend, identifier)
        consumers = require_refs(frontend, identifier)
        for consumer in consumers:
            for provider in providers:
                if provider not in ancestors(consumer):
                    errors.append(f"页面缺后端前置：{identifier} {consumer} -> {provider}")
        reachable = set(consumers)
        for consumer in consumers:
            reachable.update(ancestors(consumer))
        reachable_text = "\n".join(tasks.get(reference, "") for reference in reachable)
        test_files = re.findall(r"[a-z-]+\.spec\.ts", tests)
        if not test_files or any(name not in reachable_text for name in test_files):
            errors.append(f"交付项无对应测试任务：{identifier}")

    if returned_disposition_routes != [("A01", "orders/aftersales", "AfterSalesController")]:
        errors.append("售后池处置命令归属错误：disposeReturnedProduct 必须仅由 A01 的 orders/aftersales HTTP 发起")

    required_contracts = {"P01", "P02", "P03", "C01", "D01", "D02", "W01", "W02", "N01", "N02", "N03",
                          "O01", "O02", "A01", "A02", "F01", "F02", "F03", "F04", "F05", "F06", "R01", "R02"}
    if not required_contracts <= contracts:
        errors.append(f"基线交付项缺失：{sorted(required_contracts - contracts)}")
    sources = set()
    for row in table_rows(delivery.split("## 3.")[1].split("## 4.")[0]):
        if row[0] == "来源":
            continue
        sources.add(row[0])
        providers = require_refs(row[1], row[0])
        consumers = require_refs(row[2], row[0])
        for consumer in consumers:
            for provider in providers:
                if provider not in ancestors(consumer):
                    errors.append(f"来源缺前置：{row[0]} {consumer} -> {provider}")
    reporting_sources = {f"{owner}ReportingFeed" for owner in
                         ["Customer", "Product", "Workforce", "Production", "Order", "AfterSales", "Payroll", "Cost", "Cash"]}
    if not reporting_sources <= sources:
        errors.append(f"报表来源缺失：{sorted(reporting_sources - sources)}")

    for consumer, provider in [("3.1", "2.12"), ("4.1", "2.12"), ("6.1", "2.12"),
                               ("12.1", "10.12"), ("14.1", "13.8"), ("15.3", "14.16"),
                               ("15.6", "14.26")]:
        if provider not in ancestors(consumer):
            errors.append(f"关键门禁缺失：{consumer} -> {provider}")
    for identifier in ["1.4", "1.6", "1.9", "1.10"]:
        if re.search(r"(?:HttpCommandContractIT|CommandBatchIT)", tasks.get(identifier, "")):
            errors.append(f"过早依赖真实业务实现：{identifier}")

    version = re.search(r"版本：(\d+\.\d+)", (root / "README.md").read_text())[1]
    for name in ["README.md", "proposal.md", "design.md", "tasks.md", "coverage.md", "delivery-contracts.md", "review.md"]:
        path = root / name
        if not path.exists():
            errors.append(f"工件缺失：{name}")
            continue
        match = re.search(r"(?:版本：|提案 )(\d+\.\d+)", path.read_text())
        if not match or match[1] != version:
            errors.append(f"当前版本不一致：{name}")
    for path in root.rglob("*.md"):
        content = path.read_text()
        if any(line.rstrip() != line for line in content.splitlines()):
            errors.append(f"尾随空白：{path.name}")
        for target in re.findall(r"\]\(([^)]+)\)", content):
            if "://" in target or target.startswith("#"):
                continue
            if not (path.parent / target.split("#")[0]).exists():
                errors.append(f"本地链接失效：{path.name} -> {target}")
    return errors, {"tasks": len(tasks), "checked": checked, "requirements": len(requirements),
                    "scenarios": scenario_count, "delivery_rows": len(contracts)}


def main():
    parser = argparse.ArgumentParser(description="仅校验 OpenSpec 计划结构，不执行业务验收")
    parser.add_argument("--planning-only", action="store_true")
    arguments = parser.parse_args()
    errors, counts = validate(Path(__file__).resolve().parent, arguments.planning_only)
    if errors:
        print("计划校验失败：\n" + "\n".join(errors))
        return 1
    print(f"计划结构校验通过：{counts}")
    print("检查范围：任务依赖、要求映射、来源门禁、页面测试映射、售后池处置命令归属、版本及本地链接。")
    print("未执行业务测试；本结果不证明实现、业务语义或运行时行为正确。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
