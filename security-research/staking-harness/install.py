import pathlib, shutil, sys
here = pathlib.Path(__file__).parent; hcn = pathlib.Path(sys.argv[1])
pkg = hcn / "hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/test/handlers/staking"
pkg.mkdir(parents=True, exist_ok=True)
shutil.copy(here / "StakingRewardOverflowPropertyTest.java", pkg / "StakingRewardOverflowPropertyTest.java")
print("installed staking harness into", hcn)
