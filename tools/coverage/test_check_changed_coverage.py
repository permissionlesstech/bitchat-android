import pathlib
import tempfile
import unittest

from tools.coverage.check_changed_coverage import (
    CoverageLine,
    evaluate,
    git_diff_command,
    jacoco_relative_path,
    parse_changed_lines,
    parse_jacoco,
)


class ChangedCoverageToolTest(unittest.TestCase):
    def test_parses_added_and_modified_hunks(self) -> None:
        diff = """\
diff --git a/app/src/main/java/example/Thing.kt b/app/src/main/java/example/Thing.kt
--- a/app/src/main/java/example/Thing.kt
+++ b/app/src/main/java/example/Thing.kt
@@ -1,0 +2,3 @@
+a
+b
+c
@@ -9 +12 @@
-old
+new
"""
        self.assertEqual(
            {"app/src/main/java/example/Thing.kt": {2, 3, 4, 12}},
            parse_changed_lines(diff),
        )

    def test_parses_jacoco_source_lines(self) -> None:
        xml = """\
<report name="test">
  <package name="example">
    <sourcefile name="Thing.kt">
      <line nr="2" mi="0" ci="3"/>
      <line nr="3" mi="2" ci="0"/>
    </sourcefile>
  </package>
</report>
"""
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "report.xml"
            path.write_text(xml, encoding="utf-8")
            parsed = parse_jacoco(path)

        self.assertTrue(parsed[("example/Thing.kt", 2)].covered)
        self.assertFalse(parsed[("example/Thing.kt", 3)].covered)

    def test_evaluates_only_executable_changed_lines(self) -> None:
        changed = {"app/src/main/java/example/Thing.kt": {1, 2, 3}}
        coverage = {
            ("example/Thing.kt", 2): CoverageLine(0, 1),
            ("example/Thing.kt", 3): CoverageLine(1, 0),
        }

        self.assertEqual(
            (1, 2, ["app/src/main/java/example/Thing.kt:3"]),
            evaluate(changed, coverage),
        )

    def test_maps_both_supported_source_roots(self) -> None:
        self.assertEqual(
            "example/Thing.kt",
            jacoco_relative_path("app/src/main/java/example/Thing.kt"),
        )
        self.assertEqual(
            "example/Thing.kt",
            jacoco_relative_path("app/src/main/kotlin/example/Thing.kt"),
        )
        self.assertIsNone(jacoco_relative_path("app/src/test/example/Thing.kt"))

    def test_changed_line_diff_ignores_formatting_only_edits(self) -> None:
        command = git_diff_command("origin/main")

        self.assertIn("--ignore-all-space", command)
        self.assertIn("--unified=0", command)
        self.assertEqual("origin/main", command[command.index("--diff-filter=AM") + 1])


if __name__ == "__main__":
    unittest.main()
