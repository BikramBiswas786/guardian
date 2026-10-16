"""Install the conservation harness into a hiero-consensus-node checkout (argv[1])."""
import pathlib, re, shutil, sys

here = pathlib.Path(__file__).parent
hcn = pathlib.Path(sys.argv[1])
pkg = hcn / "hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/test/handlers/transfer"

# 1. Unit-level property test: a standalone file.
shutil.copy(here / "CustomFeeConservationPropertyTest.java", pkg / "customfees" / "CustomFeeConservationPropertyTest.java")

# 2. Multi-level additions: paste the members into CustomFeeAssessmentStepTest, before its final closing brace.
target = pkg / "CustomFeeAssessmentStepTest.java"
src = target.read_text()
additions = (here / "MultiLevelConservation_ADDITIONS.java").read_text()
additions = re.sub(r"^// SPDX-License-Identifier.*\n", "", additions)
if "import java.util.HashMap;" not in src:
    src = src.replace("import java.util.List;", "import java.util.HashMap;\nimport java.util.List;", 1)
end = src.rstrip().rfind("}")
src = src[:end] + "\n" + additions + "\n}\n"
target.write_text(src)
# 3. Hook authorization properties: a standalone file in its own package.
hooks_pkg = pkg / "hooks"
hooks_pkg.mkdir(exist_ok=True)
shutil.copy(here / "HookAuthorizationPropertyTest.java", hooks_pkg / "HookAuthorizationPropertyTest.java")
print("installed into", hcn)
