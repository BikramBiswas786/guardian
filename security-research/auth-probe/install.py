"""Install the authorization probe test into a hiero-consensus-node checkout (argv[1])."""
import pathlib, shutil, sys
here = pathlib.Path(__file__).parent
hcn = pathlib.Path(sys.argv[1])
pkg = hcn / "hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/test/handlers"
pkg.mkdir(parents=True, exist_ok=True)
shutil.copy(here / "UpdateTokenKeysAuthorizationProbeTest.java", pkg / "UpdateTokenKeysAuthorizationProbeTest.java")
pkg2 = hcn / "hedera-node/hedera-smart-contract-service-impl/src/test/java/com/hedera/node/app/service/contract/impl/test/exec/systemcontracts/hts/update/address_0x167"
pkg2.mkdir(parents=True, exist_ok=True)
shutil.copy(here / "UpdateKeysSeverityProbeTest.java", pkg2 / "UpdateKeysSeverityProbeTest.java")
print("installed auth probe into", hcn)
