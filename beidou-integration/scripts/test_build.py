"""验证单 JAR 的启动布局、原始迁移保留和运行资源范围。"""

from pathlib import Path
import json
import tempfile
import unittest
import zipfile

import build


class AssemblyContractTest(unittest.TestCase):
    def test_boot_layout_and_native_schema_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            engine = root / "engine.jar"
            with zipfile.ZipFile(engine, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"
                                 "Main-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
                                 "Start-Class: org.gms.ServerApplication\r\n"
                                 "Spring-Boot-Layers-Index: BOOT-INF/layers.idx\r\n\r\n")
                archive.writestr("META-INF/TEST.SF", "old-signature")
                archive.writestr("META-INF/NOTICE", "original-beidou-notice")
                archive.writestr("BOOT-INF/classes/org/gms/ServerApplication.class", b"original-main")
                archive.writestr("BOOT-INF/classes/org/gms/server/Trade.class", b"original-trade")
                archive.writestr("BOOT-INF/classes/application.yml", b"native-config")
                archive.writestr("BOOT-INF/classes/static/index.html", "<html><body><span>BeiDou版权</span></body></html>")
                archive.writestr("BOOT-INF/classes/db/migration/V1__native.sql", b"native-migration")
                archive.writestr("BOOT-INF/lib/native.jar", b"native-dependency")
                archive.writestr("BOOT-INF/classpath.idx", '- "BOOT-INF/lib/native.jar"\n')
                archive.writestr("BOOT-INF/layers.idx", '- "dependencies":\n  - "BOOT-INF/lib/"\n')
            woven = root / "woven"
            trade = woven / "org/gms/server/Trade.class"
            trade.parent.mkdir(parents=True)
            trade.write_bytes(b"woven-trade")
            addition = woven / "soloMapling/beidou/Bootstrap.class"
            addition.parent.mkdir(parents=True)
            addition.write_bytes(b"solo-bootstrap")
            bundle = root / "bundle.jar"
            bundle.write_bytes(b"solo-dependency")
            output = root / "SoloMapling-BeiDou.jar"

            report = build.assemble_jar(engine, woven, bundle, output,
                                        {"soloMapling/dialogue.yaml": b"hello: world"},
                                        license_files={"LICENSE": b"solo-license", "NOTICE": b"solo-notice"})

            with zipfile.ZipFile(output) as archive:
                self.assertEqual(b"native-config", archive.read("BOOT-INF/classes/application.yml"))
                self.assertEqual(b"native-migration", archive.read("BOOT-INF/classes/db/migration/V1__native.sql"))
                self.assertEqual(b"original-main", archive.read("BOOT-INF/classes/org/gms/ServerApplication.class"))
                self.assertEqual(b"woven-trade", archive.read("BOOT-INF/classes/org/gms/server/Trade.class"))
                self.assertEqual(b"hello: world", archive.read("BOOT-INF/classes/soloMapling/dialogue.yaml"))
                self.assertEqual(b"original-beidou-notice", archive.read("META-INF/NOTICE"))
                self.assertEqual(b"solo-license", archive.read("META-INF/solomapling/LICENSE"))
                self.assertEqual(b"solo-notice", archive.read("META-INF/solomapling/NOTICE"))
                metadata = json.loads(archive.read("META-INF/solomapling/source.json"))
                self.assertEqual(build.SOURCE_REPOSITORY, metadata["sourceRepository"])
                self.assertEqual(build.ENGINE_REVISION, metadata["beidouRevision"])
                html = archive.read(build.UI_INDEX).decode()
                self.assertIn("BeiDou版权", html)
                self.assertIn("SoloMapling源代码（AGPL-3.0）", html)
                self.assertIn(build.SOURCE_REPOSITORY, html)
                self.assertEqual(1, html.count('id="solomapling-source-link"'))
                self.assertNotIn("META-INF/TEST.SF", archive.namelist())
                self.assertEqual('- "dependencies":\n  - "BOOT-INF/lib/"\n',
                                 archive.read("BOOT-INF/layers.idx").decode())
                self.assertIn("BOOT-INF/classes/soloMapling/", archive.namelist())
                for name in ["BOOT-INF/lib/native.jar", "BOOT-INF/lib/" + build.RUNTIME_BUNDLE_NAME]:
                    self.assertEqual(zipfile.ZIP_STORED, archive.getinfo(name).compress_type)
                    self.assertIn(name, archive.read("BOOT-INF/classpath.idx").decode())
            self.assertEqual(["org/gms/server/Trade.class"], report["changedEngineClasses"])
            self.assertEqual(["soloMapling/beidou/Bootstrap.class"], report["addedClasses"])
            self.assertEqual(1, report["nativeMigrationFiles"])

    def test_an_added_migration_cannot_pass_artifact_validation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            original = root / "original.jar"
            derived = root / "derived.jar"
            with zipfile.ZipFile(original, "w") as archive:
                archive.writestr("BOOT-INF/classes/db/migration/V1__native.sql", "native")
            with zipfile.ZipFile(derived, "w") as archive:
                archive.writestr("BOOT-INF/classes/db/migration/V1__native.sql", "native")
                archive.writestr("BOOT-INF/classes/db/migration/V2__unexpected.sql", "unexpected")
            with self.assertRaisesRegex(RuntimeError, "迁移文件集合"):
                build.verify_native_resources(original, derived)

    def test_cosmic_engine_is_not_accepted_as_a_packaged_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "wrong.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Start-Class: org.gms.ServerApplication\n")
                archive.writestr("BOOT-INF/classes/org/gms/ServerApplication.class", b"main")
                archive.writestr("BOOT-INF/classes/net/server/Server.class", b"cosmic")
            with self.assertRaisesRegex(RuntimeError, "Cosmic"):
                build.verify_jar(jar)


class RuntimeResourcesTest(unittest.TestCase):
    def test_only_solo_runtime_data_is_included(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "src/main/java/soloMapling"
            for name in ["BotData/dialogue.yaml", "Movement/path.bin", "Movement/path.csv",
                         "BotData/names.txt", "Documents/design.txt", "BotData/source.java",
                         "BotData/image.png", "Documents/demo.mp4"]:
                path = source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            self.assertEqual({"soloMapling/BotData/dialogue.yaml", "soloMapling/Movement/path.bin",
                              "soloMapling/Movement/path.csv", "soloMapling/BotData/names.txt"},
                             set(build.runtime_resources(root)))

    def test_integration_cannot_replace_native_config_or_migrations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "beidou-integration/src/main/resources/db/migration/V99__bot.sql"
            path.parent.mkdir(parents=True)
            path.write_text("CREATE TABLE bot_data(id INT)")
            with self.assertRaisesRegex(RuntimeError, "数据库迁移"):
                build.runtime_resources(root)


if __name__ == "__main__":
    unittest.main()
