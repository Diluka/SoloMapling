#!/usr/bin/env python3
"""在不写入北斗 submodule 的前提下编译、织入并组装服务 JAR。"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile


INTEGRATION = Path(__file__).resolve().parents[1]
REPOSITORY = INTEGRATION.parent
TARGET = INTEGRATION / "target"
ENGINE = REPOSITORY / "upstream" / "BeiDou-Server"
ENGINE_REVISION = "75a86268738ca8845e06ea9d59ec36d15b22c91b"
ENGINE_COMPILER_OPTIONS = [
    "-Dmaven.test.skip=true",
    "-Dmaven.compiler.source=21",
    "-Dmaven.compiler.target=21",
    "-Dmaven.compiler.parameters=true",
]
RESOURCE_SUFFIXES = {".yaml", ".yml", ".bin", ".csv", ".txt"}
DOCUMENT_DIRECTORIES = {"documents", "docs", "handbook"}
RUNTIME_BUNDLE_NAME = "solomapling-runtime-dependencies.jar"
SOURCE_REPOSITORY = "https://github.com/Diluka/SoloMapling"
UI_INDEX = "BOOT-INF/classes/static/index.html"


def run(arguments: list[str], *, cwd: Path = REPOSITORY, capture: bool = False) -> str:
    display = list(arguments)
    for index, argument in enumerate(display):
        if index and display[index - 1] in {"-cp", "-classpath", "--class-path"} and len(argument) > 250:
            display[index] = f"<classpath，{len(argument.split(os.pathsep))}项>"
    print("执行：" + shlex.join(display), flush=True)
    result = subprocess.run(
        arguments, cwd=cwd, text=True, check=True,
        stdout=subprocess.PIPE if capture else None,
    )
    return result.stdout if capture else ""


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fresh_directory(path: Path) -> None:
    # 保留上次生成结果供诊断；新一次编译不能混入已删除源码的旧 class。
    if path.exists():
        path.rename(path.with_name(f"{path.name}.previous-{time.time_ns()}"))
    path.mkdir(parents=True)


def source_fingerprint() -> str:
    """把编译源码、测试及运行资源绑定到同一轮构建，拒绝混入并发编辑。"""
    fingerprint = hashlib.sha256()
    roots = [REPOSITORY / "src/main/java/soloMapling", INTEGRATION / "src/main",
             REPOSITORY / "src/test/java/soloMapling/ArtificialPlayer/LlmSystem",
             INTEGRATION / "src/test/java", INTEGRATION / "scripts"]
    for root in roots:
        if root.is_dir():
            for path in sorted(root.rglob("*")):
                if path.is_file() and path.suffix in {".java", ".aj", ".py", ".sh"}:
                    fingerprint.update(path.relative_to(REPOSITORY).as_posix().encode())
                    fingerprint.update(path.read_bytes())
    for name, content in sorted(runtime_resources(REPOSITORY).items()):
        fingerprint.update(name.encode())
        fingerprint.update(content)
    for path in [INTEGRATION / "pom.xml", Path(__file__), REPOSITORY / "LICENSE", REPOSITORY / "NOTICE"]:
        if path.is_file():
            fingerprint.update(path.relative_to(REPOSITORY).as_posix().encode())
            fingerprint.update(path.read_bytes())
    return fingerprint.hexdigest()


def assert_engine_unchanged() -> str:
    if not (ENGINE / "pom.xml").is_file():
        raise RuntimeError("北斗 submodule 尚未初始化，请先执行 git submodule update --init。")
    revision = run(["git", "rev-parse", "HEAD"], cwd=ENGINE, capture=True).strip()
    if revision != ENGINE_REVISION:
        raise RuntimeError(f"北斗版本不匹配：要求 {ENGINE_REVISION}，实际 {revision}。")
    changes = run(
        ["git", "status", "--porcelain", "--untracked-files=all"], cwd=ENGINE, capture=True
    ).strip()
    if changes:
        raise RuntimeError("北斗 submodule 必须保持原样，发现改动：\n" + changes)
    return revision


def maven_command(settings: Path | None) -> list[str]:
    command = shlex.split(os.environ.get("MAVEN_COMMAND", "mvn"))
    if shutil.which(command[0]) is None and command == ["mvn"]:
        command = [str(INTEGRATION / "scripts" / "mvn-container.sh")]
    command += ["-B", "-ntp"]
    if settings:
        # Maven 的容器回退只挂载本仓库；外部 settings 按原样复制进忽略的构建目录。
        local_settings = TARGET / "maven-settings.xml"
        if settings.resolve() != local_settings:
            shutil.copyfile(settings, local_settings)
        command += ["-s", str(local_settings)]
    return command


def build_engine(maven: list[str], skip: bool) -> Path:
    workspace = TARGET / "engine-source"
    jar = workspace / "gms-server" / "target" / "BeiDou.jar"
    if skip:
        if not jar.is_file():
            raise RuntimeError("没有可复用的北斗构建产物，不能使用 --skip-engine-build。")
        stamp = json.loads((workspace / "source-reference.json").read_text())
        if stamp["revision"] != ENGINE_REVISION or stamp["jarSha256"] != digest(jar):
            raise RuntimeError("缓存的北斗 JAR 与源码记录不匹配，请重新构建。")
        if stamp.get("compilerOptions") != ENGINE_COMPILER_OPTIONS:
            raise RuntimeError("缓存的北斗编译选项不匹配，请移除 --skip-engine-build 后重新构建。")
        return jar

    workspace.mkdir(parents=True, exist_ok=True)
    archive = subprocess.check_output(
        ["git", "archive", "HEAD", "pom.xml", "gms-server/pom.xml", "gms-server/src/main"],
        cwd=ENGINE,
    )
    with tarfile.open(fileobj=io.BytesIO(archive)) as source:
        source.extractall(workspace, filter="data")
    # 显式 clean 防止增量编译沿用缺少 MethodParameters 的旧 class。
    run(maven + ["-f", str(workspace / "pom.xml"), "-pl", "gms-server", "-am",
                 "clean", "package"] + ENGINE_COMPILER_OPTIONS, cwd=workspace)
    if not jar.is_file():
        raise RuntimeError("北斗编译未生成预期的 gms-server/target/BeiDou.jar。")
    (workspace / "source-reference.json").write_text(json.dumps({
        "revision": ENGINE_REVISION,
        "jarSha256": digest(jar),
        "compilerOptions": ENGINE_COMPILER_OPTIONS,
    }, indent=2) + "\n")
    return jar


def build_dependencies(maven: list[str], skip: bool) -> tuple[Path, Path]:
    bundle = TARGET / "solo-runtime-dependencies.jar"
    compiler = TARGET / "tools" / "aspectjtools.jar"
    stamp = TARGET / "dependency-reference.json"
    pom = INTEGRATION / "pom.xml"
    if skip:
        if not bundle.is_file() or not compiler.is_file() or not stamp.is_file():
            raise RuntimeError("没有完整的依赖缓存，不能使用 --skip-dependencies。")
        if json.loads(stamp.read_text())["pomSha256"] != digest(pom):
            raise RuntimeError("依赖 POM 已变化，请重新构建依赖。")
    else:
        run(maven + ["-f", str(pom), "package", "-Dmaven.test.skip=true"])
        stamp.write_text(json.dumps({"pomSha256": digest(pom)}, indent=2) + "\n")
    return bundle, compiler


def unpack_compile_input(engine_jar: Path) -> tuple[Path, list[Path]]:
    original_classes = TARGET / "engine-classes"
    libraries = TARGET / "engine-libraries"
    fresh_directory(original_classes)
    fresh_directory(libraries)
    with zipfile.ZipFile(engine_jar) as source:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            if entry.filename.startswith("BOOT-INF/classes/") and entry.filename.endswith(".class"):
                target = original_classes / entry.filename.removeprefix("BOOT-INF/classes/")
            elif entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                target = libraries / Path(entry.filename).name
            else:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))
    if not (original_classes / "org/gms/ServerApplication.class").is_file():
        raise RuntimeError("输入不是预期的北斗 Spring Boot JAR。")
    return original_classes, sorted(libraries.glob("*.jar"))


def compile_and_weave(classes: Path, libraries: list[Path], bundle: Path, compiler: Path) -> Path:
    output = TARGET / "woven-classes"
    fresh_directory(output)
    source_roots = [
        REPOSITORY / "src/main/java/soloMapling",
        INTEGRATION / "src/main/java",
        INTEGRATION / "src/main/aspect",
    ]
    source_roots = [root for root in source_roots if root.is_dir()]
    if not any(root.suffix == ".aj" for base in source_roots for root in base.rglob("*.aj")):
        raise RuntimeError("缺少北斗接入切面，拒绝生成未织入的服务包。")
    lint = TARGET / "aspectj-lint.properties"
    lint.write_text("adviceDidNotMatch=error\ninvalidAbsoluteTypeName=error\n")
    aspectjrt = TARGET / "tools/aspectjrt.jar"
    run([
        os.environ.get("JAVA_COMMAND", "java"), "-Xmx2g", "-cp", str(compiler),
        "org.aspectj.tools.ajc.Main", "-21", "-parameters", "-proc:none", "-encoding", "UTF-8",
        "-inpath", str(classes), "-classpath", os.pathsep.join(map(str, [classes, aspectjrt, bundle] + libraries)),
        "-sourceroots", os.pathsep.join(map(str, source_roots)),
        "-d", str(output), "-showWeaveInfo", "-Xlintfile", str(lint),
    ])
    if not any(output.rglob("*.class")):
        raise RuntimeError("AspectJ 未产生编译结果。")
    return output


def run_tests(woven: Path, libraries: list[Path], bundle: Path) -> None:
    run([sys.executable, "-m", "unittest", "discover", "-s", str(INTEGRATION / "scripts"),
         "-p", "test_*.py", "-v"])
    console = TARGET / "tools/junit-platform-console-standalone.jar"
    sources = sorted((REPOSITORY / "src/test/java/soloMapling/ArtificialPlayer/LlmSystem").glob("*.java"))
    sources += sorted((INTEGRATION / "src/test/java").rglob("*.java"))
    if not sources:
        raise RuntimeError("未找到独立集成测试，拒绝将未验证构建报告为完成。")
    tests = TARGET / "test-classes"
    fresh_directory(tests)
    test_resources = TARGET / "test-resources"
    fresh_directory(test_resources)
    for name, content in runtime_resources(REPOSITORY).items():
        resource = test_resources / name
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_bytes(content)
    test_libraries = sorted((TARGET / "tools/test-libraries").glob("*.jar"))
    classpath = os.pathsep.join(map(str, [woven, bundle, console] + libraries + test_libraries))
    run([os.environ.get("JAVAC_COMMAND", "javac"), "--release", "21", "-proc:none",
         "-encoding", "UTF-8", "-classpath", classpath, "-d", str(tests)] + list(map(str, sources)))
    runtime = os.pathsep.join(map(str, [tests, woven, test_resources, bundle] + libraries + test_libraries))
    run([os.environ.get("JAVA_COMMAND", "java"), "-jar", str(console), "execute",
         "--class-path", runtime, "--scan-class-path", str(tests), "--fail-if-no-tests",
         "--disable-banner", "--reports-dir", str(TARGET / "test-reports")])


def runtime_resources(repository: Path) -> dict[str, bytes]:
    resources: dict[str, bytes] = {}
    root = repository / "src/main/java/soloMapling"
    if root.is_dir():
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root)
            if (path.is_file() and path.suffix.lower() in RESOURCE_SUFFIXES
                    and not any(part.lower() in DOCUMENT_DIRECTORIES for part in relative.parts)):
                resources["soloMapling/" + relative.as_posix()] = path.read_bytes()
    additions = repository / "beidou-integration/src/main/resources"
    if additions.is_dir():
        for path in sorted(additions.rglob("*")):
            if path.is_file():
                relative = path.relative_to(additions).as_posix()
                if relative in {"application.yml", "application.yaml", "application.properties"} or relative.startswith("db/migration/"):
                    raise RuntimeError("Solo 集成资源不得替换北斗配置或数据库迁移：" + relative)
                resources[relative] = path.read_bytes()
    return resources


def is_signature(name: str) -> bool:
    upper = name.upper()
    return upper.startswith("META-INF/") and upper.endswith((".SF", ".RSA", ".DSA", ".EC"))


def add_source_link(content: bytes) -> bytes:
    html = content.decode("utf-8")
    if 'id="solomapling-source-link"' in html:
        return content
    link = (f'<a id="solomapling-source-link" href="{SOURCE_REPOSITORY}" '
            'target="_blank" rel="noopener noreferrer" '
            'style="position:fixed;left:12px;bottom:8px;z-index:1000;font-size:12px;'
            'padding:4px 8px;border-radius:4px;background:rgba(255,255,255,.92);color:#1d2129">'
            'SoloMapling源代码（AGPL-3.0）</a>')
    if "</body>" not in html:
        raise RuntimeError("管理页面缺少 body 结束标签，无法加入源码入口。")
    return html.replace("</body>", link + "</body>", 1).encode("utf-8")


def assemble_jar(engine_jar: Path, woven: Path, bundle: Path, output: Path,
                 resources: dict[str, bytes], ui_jar: Path | None = None,
                 license_files: dict[str, bytes] | None = None,
                 source_metadata: dict | None = None) -> dict:
    replacements = {
        "BOOT-INF/classes/" + path.relative_to(woven).as_posix(): path.read_bytes()
        for path in sorted(woven.rglob("*.class"))
    }
    replacements.update({"BOOT-INF/classes/" + name: content for name, content in resources.items()})
    replacements.update({"META-INF/solomapling/" + name: content
                         for name, content in (license_files or {}).items()})
    replacements["META-INF/solomapling/source.json"] = (
        json.dumps({"sourceRepository": SOURCE_REPOSITORY, "beidouRevision": ENGINE_REVISION,
                    **(source_metadata or {})}, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if ui_jar:
        with zipfile.ZipFile(ui_jar) as ui:
            replacements.update({entry.filename: ui.read(entry)
                                 for entry in ui.infolist()
                                 if entry.filename.startswith("BOOT-INF/classes/static/") and not entry.is_dir()})
    runtime_entry = "BOOT-INF/lib/" + RUNTIME_BUNDLE_NAME
    replacements[runtime_entry] = bundle.read_bytes()
    changed_classes: list[str] = []
    added_classes: list[str] = []
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".jar.building")
    with zipfile.ZipFile(engine_jar) as source, zipfile.ZipFile(temporary, "w") as target:
        libraries = {entry.filename for entry in source.infolist()
                     if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar")}
        libraries.add(runtime_entry)
        written: set[str] = set()
        for entry in source.infolist():
            name = entry.filename
            if name in written:
                raise RuntimeError("北斗 JAR 存在重复条目：" + name)
            if is_signature(name) or name in {"BOOT-INF/classpath.idx", "META-INF/INDEX.LIST"}:
                continue
            content = source.read(entry)
            if name in replacements:
                replacement = replacements.pop(name)
                if name.endswith(".class") and content != replacement:
                    changed_classes.append(name.removeprefix("BOOT-INF/classes/"))
                content = replacement
            if name == UI_INDEX:
                content = add_source_link(content)
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                entry.compress_type = zipfile.ZIP_STORED
            target.writestr(entry, content)
            written.add(name)
        for name, content in sorted(replacements.items()):
            if name in written:
                raise RuntimeError("组装时条目冲突：" + name)
            if name == UI_INDEX:
                content = add_source_link(content)
            # 显式目录项支持基于 ClassLoader 的资源目录发现。
            for parent in reversed(Path(name).parents):
                if parent.as_posix() == ".":
                    continue
                directory = parent.as_posix() + "/"
                if directory not in written:
                    target.writestr(directory, b"", compress_type=zipfile.ZIP_STORED)
                    written.add(directory)
            compression = zipfile.ZIP_STORED if name.startswith("BOOT-INF/lib/") else zipfile.ZIP_DEFLATED
            target.writestr(name, content, compress_type=compression)
            if name.endswith(".class"):
                added_classes.append(name.removeprefix("BOOT-INF/classes/"))
        target.writestr("BOOT-INF/classpath.idx", "".join(f'- "{name}"\n' for name in sorted(libraries)))
    verify_jar(temporary)
    migration_count = verify_native_resources(engine_jar, temporary)
    temporary.replace(output)
    return {"changedEngineClasses": changed_classes, "addedClasses": added_classes,
            "nativeMigrationFiles": migration_count, "nativeResourcesVerified": True}


def verify_native_resources(engine_jar: Path, output: Path) -> int:
    """逐字节保留北斗配置和迁移；这不是对运行数据兼容性的替代验证。"""
    prefix = "BOOT-INF/classes/db/migration/"
    with zipfile.ZipFile(engine_jar) as original, zipfile.ZipFile(output) as derived:
        original_migrations = {name for name in original.namelist()
                               if name.startswith(prefix) and not name.endswith("/")}
        derived_migrations = {name for name in derived.namelist()
                              if name.startswith(prefix) and not name.endswith("/")}
        if original_migrations != derived_migrations:
            raise RuntimeError("产物的北斗数据库迁移文件集合发生变化。")
        expected = original_migrations | {
            name for name in original.namelist()
            if name in {"BOOT-INF/classes/application.yml", "BOOT-INF/classes/application.yaml",
                        "BOOT-INF/classes/application.properties"}
        }
        for name in expected:
            if original.read(name) != derived.read(name):
                raise RuntimeError("产物改变了北斗原始配置或迁移：" + name)
        return len(original_migrations)


def verify_jar(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise RuntimeError("产物包含重复 ZIP 条目。")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        manifest = manifest.replace("\r\n ", "").replace("\n ", "")
        if "Start-Class: org.gms.ServerApplication" not in manifest:
            raise RuntimeError("产物未保留北斗原始启动入口。")
        if "BOOT-INF/classes/org/gms/ServerApplication.class" not in names:
            raise RuntimeError("产物缺少北斗主类。")
        for entry in archive.infolist():
            if entry.filename.startswith("BOOT-INF/lib/") and entry.filename.endswith(".jar"):
                if entry.compress_type != zipfile.ZIP_STORED:
                    raise RuntimeError("Spring Boot 嵌套依赖必须使用 STORED：" + entry.filename)
        if "Spring-Boot-Layers-Index:" in manifest and "BOOT-INF/layers.idx" not in names:
            raise RuntimeError("产物的分层索引声明缺少对应文件。")
        if any(name.startswith("BOOT-INF/classes/net/server/") for name in names):
            raise RuntimeError("产物误带原版 Cosmic 引擎。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--settings", type=Path, default=None)
    parser.add_argument("--skip-engine-build", action="store_true")
    parser.add_argument("--skip-dependencies", action="store_true")
    parser.add_argument("--skip-tests", action="store_true", help="仅供调试编译；产物不代表通过验收")
    parser.add_argument("--prepare-only", action="store_true", help="只准备原样北斗编译结果与依赖")
    parser.add_argument("--ui-jar", type=Path, help="可选：从官方发布 JAR 复用管理页面")
    parser.add_argument("--output", type=Path, default=REPOSITORY / "target/SoloMapling-BeiDou.jar")
    args = parser.parse_args()
    TARGET.mkdir(parents=True, exist_ok=True)
    revision = assert_engine_unchanged()
    maven = maven_command(args.settings)
    engine_jar = build_engine(maven, args.skip_engine_build)
    bundle, compiler = build_dependencies(maven, args.skip_dependencies)
    classes, libraries = unpack_compile_input(engine_jar)
    if args.prepare_only:
        assert_engine_unchanged()
        print("北斗原样编译结果与集成依赖已准备完成。", flush=True)
        return
    source_reference = source_fingerprint()
    woven = compile_and_weave(classes, libraries, bundle, compiler)
    if not args.skip_tests:
        run_tests(woven, libraries, bundle)
    if source_fingerprint() != source_reference:
        raise RuntimeError("构建期间源码、测试或资源发生变化，请等待编辑完成后重新构建。")
    licenses = {name: (REPOSITORY / name).read_bytes()
                for name in ("LICENSE", "NOTICE") if (REPOSITORY / name).is_file()}
    source_metadata = {
        "soloGitRevision": run(["git", "rev-parse", "HEAD"], capture=True).strip(),
        "soloWorkingTreeDirty": bool(run(["git", "status", "--porcelain"], capture=True).strip()),
        "soloSourcesSha256": source_reference,
        "integration": "AspectJ binary weaving",
    }
    result = assemble_jar(engine_jar, woven, bundle, args.output.resolve(),
                          runtime_resources(REPOSITORY), args.ui_jar, licenses, source_metadata)
    assert_engine_unchanged()
    if source_fingerprint() != source_reference:
        raise RuntimeError("打包期间源码或资源发生变化，本轮产物不能作为交付结果。")
    result.update({"engineRevision": revision, "originalEngineJarSha256": digest(engine_jar),
                   "outputSha256": digest(args.output), "output": str(args.output.resolve()),
                   "testsExecuted": not args.skip_tests, "soloSourcesSha256": source_reference})
    (TARGET / "build-report.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("构建完成：" + str(args.output.resolve()), flush=True)
    print(f"北斗源码未修改；产物织入了 {len(result['changedEngineClasses'])} 个原有类。", flush=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        print(f"构建失败：命令退出码 {failure.returncode}，请查看上方具体诊断。", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, FileNotFoundError) as failure:
        print("构建失败：" + str(failure), file=sys.stderr)
        sys.exit(1)
