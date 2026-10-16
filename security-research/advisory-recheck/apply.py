"""Apply advisory fix patches / regression tests to a hiero-consensus-node checkout.

usage: apply.py <hcn-dir> <mode>
  q776-baseline : add the GHSA-q776 regression tests only (unpatched main -> tests should FAIL if bug still present)
  q776-fix      : add the tests AND the fix (0x167 + 0x16c decoders, wide-keyType guard)
  jfxq-baseline : add the GHSA-jfxq regression test only (unpatched main -> should FAIL if 326 remap still present)
  jfxq-fix      : add the test AND the fix (dispatch convenience constructors default to NOOP customizer)
"""
import pathlib, shutil, sys

hcn = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
here = pathlib.Path(__file__).parent

SC = hcn / "hedera-node/hedera-smart-contract-service-impl/src"
MAIN = SC / "main/java/com/hedera/node/app/service/contract/impl/exec/systemcontracts/hts"
TEST = SC / "test/java/com/hedera/node/app/service/contract/impl/test/exec/systemcontracts/hts"


def edit(path, old, new, count=1):
    s = path.read_text()
    n = s.count(old)
    if n != count:
        sys.exit(f"PATCH MISMATCH in {path.name}: expected {count} occurrence(s), found {n}:\n{old}")
    path.write_text(s.replace(old, new))


def copy(name, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(here / name, dest)


if mode.startswith("q776"):
    copy("UpdateKeysDecoderTest.java", TEST / "update/address_0x167/UpdateKeysDecoderTest.java")
    copy("UpdateKeysDecoder16cTest.java", TEST / "update/address_0x16c/UpdateKeysDecoder16cTest.java")
    if mode == "q776-fix":
        common = MAIN / "update/UpdateCommonDecoder.java"
        edit(common,
             "    @Nullable\n    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {\n\n"
             "        final var call = decodeCall(attempt);\n",
             "    /** keyType bits updateTokenKeys can apply at 0x167 (admin..pause; metadata 0x80 is not applied there). */\n"
             "    protected static final int UPDATE_KEYS_SUPPORTED_MASK_167 = 0x7F;\n"
             "    /** keyType bits updateTokenKeys can apply at 0x16c (adds the metadata key, 0x80). */\n"
             "    protected static final int UPDATE_KEYS_SUPPORTED_MASK_16C = 0xFF;\n\n"
             "    /**\n"
             "     * True iff the raw key list is non-empty, every keyType is a positive value within {@code supportedMask}\n"
             "     * (checked on the full uint256, before any int narrowing), and no two entries address the same key slot.\n"
             "     * Anything else would dispatch an update that changes nothing yet reports SUCCESS, or silently drop\n"
             "     * conflicting key material (GHSA-q776-f4m7-g642).\n"
             "     */\n"
             "    protected static boolean isMeaningfulKeyUpdate(@NonNull final Tuple[] tokenKeysTuples, final int supportedMask) {\n"
             "        if (tokenKeysTuples.length == 0) {\n"
             "            return false;\n"
             "        }\n"
             "        int slotsInUse = 0;\n"
             "        for (final var tokenKeyTuple : tokenKeysTuples) {\n"
             "            final var rawKeyType = (BigInteger) tokenKeyTuple.get(KEY_TYPE);\n"
             "            if (rawKeyType.signum() <= 0 || rawKeyType.bitLength() > 31) {\n"
             "                return false;\n"
             "            }\n"
             "            final int keyType = rawKeyType.intValue();\n"
             "            if ((keyType & ~supportedMask) != 0 || (slotsInUse & keyType) != 0) {\n"
             "                return false;\n"
             "            }\n"
             "            slotsInUse |= keyType;\n"
             "        }\n"
             "        return true;\n"
             "    }\n\n"
             "    @Nullable\n    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {\n\n"
             "        final var call = decodeCall(attempt);\n"
             "        if (!isMeaningfulKeyUpdate(call.get(TOKEN_KEYS), UPDATE_KEYS_SUPPORTED_MASK_167)) {\n"
             "            return null;\n"
             "        }\n")
        d16c = MAIN / "update/address_0x16c/UpdateDecoder.java"
        edit(d16c,
             "    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {\n\n"
             "        final var call = decodeCall(attempt);\n",
             "    public TransactionBody decodeTokenUpdateKeys(@NonNull final HtsCallAttempt attempt) {\n\n"
             "        final var call = decodeCall(attempt);\n"
             "        // This override bypasses the 0x167 decoder, so it must apply the same guard (GHSA-q776-f4m7-g642)\n"
             "        if (!isMeaningfulKeyUpdate(call.get(TOKEN_KEYS), UPDATE_KEYS_SUPPORTED_MASK_16C)) {\n"
             "            return null;\n"
             "        }\n")

elif mode.startswith("jfxq"):
    copy("InvalidSignatureConsistencyTest.java", TEST / "InvalidSignatureConsistencyTest.java")
    if mode == "jfxq-fix":
        d = MAIN / "DispatchForResponseCodeHtsCall.java"
        s = d.read_text()
        n = s.count("                STANDARD_FAILURE_CUSTOMIZER,\n")
        if n != 3:
            sys.exit(f"PATCH MISMATCH: expected 3 convenience-constructor uses of STANDARD_FAILURE_CUSTOMIZER, found {n}")
        s = s.replace("                STANDARD_FAILURE_CUSTOMIZER,\n", "                FailureCustomizer.NOOP_CUSTOMIZER,\n")
        start = s.index("    /**\n     * The \"standard\" failure customizer")
        end = s.index("(body, code, enhancement) -> standardized(code);\n", start) + len("(body, code, enhancement) -> standardized(code);\n")
        s = s[:start] + s[end:].lstrip("\n")
        if "standardized(" not in s:
            s = s.replace("import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.standardized;\n", "")
        d.write_text(s)
else:
    sys.exit("unknown mode " + mode)

print("applied", mode, "to", hcn)
