"""Install the fee-exemption conservation harness into a hiero-consensus-node checkout (argv[1])."""
import pathlib, shutil, sys

here = pathlib.Path(__file__).parent
hcn = pathlib.Path(sys.argv[1])
pkg = hcn / ("hedera-node/hedera-token-service-impl/src/test/java/com/hedera/node/app/service/token/impl/"
             "test/handlers/transfer/customfees")
pkg.mkdir(parents=True, exist_ok=True)
shutil.copy(here / "FeeExemptionConservationPropertyTest.java", pkg / "FeeExemptionConservationPropertyTest.java")
print("installed fee-exemption harness into", hcn)
