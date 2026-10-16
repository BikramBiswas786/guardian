import pathlib, shutil, sys
here = pathlib.Path(__file__).parent; mn = pathlib.Path(sys.argv[1])
pkg = mn / "importer/src/test/java/org/hiero/mirror/importer/reader/balance/line"
pkg.mkdir(parents=True, exist_ok=True)
shutil.copy(here / "test" / "BalanceParserIntTruncationTest.java", pkg / "BalanceParserIntTruncationTest.java")
print("installed balance-parser evidence test into", mn)
