# Hedera Testnet On-Chain Evidence

This document records the on-chain proofs for the AMS-III.AV v9.0 + VMR0015 v1.0 (v6.9.0) policy submission. All data below is independently verifiable via the Hedera Mirror Node REST API or HashScan.

## Identifiers

| Item | Value |
|---|---|
| Network | Hedera testnet |
| Schema topic ID | [`0.0.8846119`](https://hashscan.io/testnet/topic/0.0.8846119) |
| Policy instance topic ID | [`0.0.8855568`](https://hashscan.io/testnet/topic/0.0.8855568) |
| Policy uuid | `f8009246-74bd-4fa1-9e7c-c4f93bd54109` |
| Policy version | 1.1.1 |
| Token (ER-SDW VCU) | `0.0.8850715` |
| Standard Registry account | `0.0.8845724` |
| Owner DID | `did:hedera:testnet:FTYpFSq3k8DSeRK65zYYHtngcf4iChkzn8KTq3Qnq9cD_0.0.8845725` |

## Policy and schema package publish events (schema topic 0.0.8846119)

Pulled from `https://testnet.mirrornode.hedera.com/api/v1/topics/0.0.8846119/messages`.

| seq | consensus_timestamp | type | action | version | document_cid | childId / name |
|---:|---|---|---|---|---|---|
| 22 | 1777937987.829879000 | Topic | create-topic | — | — | `0.0.8855568` (policy instance topic) |
| 21 | 1777937982.884334127 | Instance-Policy | publish-policy | 1.1.1 | — | AMS-III.AV v9.0 + VMR0015 v1.0 (v6.9.0) |
| 20 | 1777937966.182722439 | Schema-Package | publish-schemas | 1.1.1 | `QmNN9xUAbTXTJCgB7RPoikaLvQMtbbfotgyqGrAyA5AF7y` | full schema package |
| 19 | 1777934435.271934000 | Schema | publish-schema | 1.2.4 | `QmUteVvoRnm5y2bSdDR1PPTxRsUge49L3PJBdFjN7s3uYj` | Project Registration (newer; not bound in policy) |
| 18 | 1777934140.010653000 | Schema | publish-schema | 1.2.2 | `QmSDPiwbbZnR6NFAtV4NXGUfydb6HFMSKeiozio2ufkNzx` | Water Quality Survey (newer; not bound) |
| 17 | 1777933893.947544874 | Schema | publish-schema | 1.2.0 | `QmUdpE9V76ZeUokt4XQAsZSYvvhXcsXUPda6QGRZZLB6Aa` | Annual Monitoring (newer; not bound) |
| 16 | 1777874847.754614366 | Schema | publish-schema | 1.1.6 | `QmSoMiDBopeptDkfeSMfsMZ2Lw1h6szsZX8dM52p2y8eKB` | VVB Verification Report |
| 15 | 1777874555.620317306 | Schema | publish-schema | 1.1.0 | `QmRbfF1dpeNok7SQrFujWoQ8Tb1M3ZF6UdwWt8oRnJb8au` | Owner Confirmation |
| 14 | 1777874523.049586620 | Schema | publish-schema | 1.1.6 | `QmUPzLsoizDq8xG1wEZG4LWSGcVRuXFszvbkDd6DJ276N9` | VVB Validation Report |
| 13 | 1777834526.377781414 | Schema | publish-schema | 1.1.0 | `QmTzD2n7KApHYVgWJeqdWKUk8Hs9SyDE9DtsPt85c2RTUP` | Water Quality Survey |
| 12 | 1777834516.199586000 | Schema | publish-schema | 1.1.0 | `QmWw2MKL4ec3DhqusNDEYF9NTZKLkdPBkjy7b9rRjgjsGn` | Project Registration |
| 11 | 1777834506.511494006 | Schema | publish-schema | 1.1.0 | `QmZodb9qAt4ekkVX5S4rMYgU8qBLNZPbokMRYGAYb7bfrV` | VVB Validation Report (older) |
| 10 | 1777834496.036421000 | Schema | publish-schema | 1.1.0 | `QmXYgXFc9F1334FZycW3tPUTyWojuUN293YEihMrE8y9iN` | Annual Monitoring |
|  9 | 1777834485.573698000 | Schema | publish-schema | 1.1.0 | `QmUnQ4D6p4gwwJeskAdBi85HKFMacpRL9TyUQuaZeFJ2YS` | VVB Accreditation |
|  8 | 1777834475.398299107 | Schema | publish-schema | 1.1.0 | `QmRXToKh74ubJFwYg4yvmGLdiTijjD4bH6nHZqZjhqq8n8` | VVB Verification Report (older) |
|  7 | 1777834465.508870000 | Schema | publish-schema | 1.1.0 | `QmRh8fi9N8wkg9zU7gBz5RzEj7iNjKCbhVnXVFTHFw5wXC` | Calculation Results |
|  6 | 1777834456.115644100 | Schema | publish-schema | 1.1.5 | `QmQa8wF7dhVpfWpNG2wZuTmkyzt5AYabWGY8ddhoUAPzFJ` | Retirement Request |
|  5 | 1777834446.311113205 | Schema | publish-schema | 1.1.5 | `QmRAt4L4R8PFTg5t15ZnYV7SBbeHm7rPxM5R6LLo2Bjpx5` | HCS Telemetry |
|  4 | 1777834431.901185000 | Schema | publish-schema | 1.1.5 | `Qmbpz7mM5LbPp4WQroMYzn7G91chSZ14yTRP7PPqQByB39` | Issuance Metadata |
|  3 | 1777834422.415872314 | Schema | publish-schema | 1.1.5 | `QmaXNt2aNL4RAxVoWGRntgDz37qfCUPNEnNydjKtKoA1JC` | Population Census |
|  2 | 1777834398.585117578 | Schema-Package | publish-system-schemas | — | `QmWhDPNHhT7ycutkNsLy9YBe373Zxqh6GdQRYP8PjfLA5b` | system schemas |
|  1 | (topic create) | Topic | create-topic | — | — | schema topic created |

## Note on schema versions

The current policy file (`AMS-III.AV_VMR0015_v6.9.0.policy`) binds to schemas at versions `1.1.0`, `1.1.5`, and `1.1.6`. Three newer schemas at `1.2.0/1.2.2/1.2.4` (Project Registration, Annual Monitoring, Water Quality Survey — sequence 17, 18, 19) are also published on the topic but not yet bound in the policy. They will be linked in a follow-up policy revision once tested.

## Reproducing the verification

```bash
# Pull all messages from the schema topic
curl "https://testnet.mirrornode.hedera.com/api/v1/topics/0.0.8846119/messages?limit=25&order=desc"

# Verify the policy publish at sequence 21
curl "https://testnet.mirrornode.hedera.com/api/v1/topics/0.0.8846119/messages/21"

# Pull the schema package from IPFS
curl "https://ipfs.io/ipfs/QmNN9xUAbTXTJCgB7RPoikaLvQMtbbfotgyqGrAyA5AF7y"
```

## Caveats

- Testnet HBAR has no economic value; this is a methodology-and-pipeline reference, not an active credit issuance.
- The MGS instance hosting the policy is custodial; the user does not hold private keys to the operator wallet.
- No accredited VVBs are currently registered against this policy. The VVB DID independence gate is implemented and tested in the calc engine but has not been exercised against real third-party VVB DIDs.
