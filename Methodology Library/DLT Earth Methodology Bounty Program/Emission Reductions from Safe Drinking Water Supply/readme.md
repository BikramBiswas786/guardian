# AMS-III.AV v9.0 + VMR0015 v1.0 — Safe Drinking Water dMRV (v6.9.0)

## Methodology

This is a Guardian policy implementation of two combined methodologies for safe drinking water carbon credits:

- **AMS-III.AV v9.0** — Low greenhouse gas emitting safe drinking water production systems (UNFCCC CDM small-scale methodology, §5.3 Eq. 1 baseline)
- **VMR0015 v1.0** — Verra revision applying VVB validation/verification rules and crediting period boundaries

The policy quantifies emission reductions from projects that supply safe drinking water using low-GHG technologies (e.g., hydropower-supplied UV, ozone, ultrafiltration, or boiling alternatives), replacing baseline household water boiling with biomass and fossil fuels.

## Need and use

The methodology applies to projects that:
- Supply safe drinking water to households or institutions in regions where boiling with non-renewable biomass or fossil fuels is the baseline practice
- Use a defined low-GHG technology (renewable-electric, solar-thermal, gravity-fed)
- Demonstrate microbiological water safety against WHO Drinking Water Guidelines

Emission reductions arise from the avoided combustion of baseline fuels. The methodology requires per-project measurement of the functional fraction of the appliances, fuel mix, and grid emission factors, with annual monitoring and independent verification.

## Implementation overview

### Roles

- **Project Developer** — Registers the project, submits Annual Monitoring + Water Quality Survey VCs each crediting period.
- **VVB (Validation and Verification Body)** — Two independent VVBs sign Validation and Verification Reports. The policy enforces DID independence: the same DID may not sign both reports.
- **Standard Registry (Owner)** — Reviews calc-engine output, signs Owner Confirmation, mints VCU + buffer tokens.

### Calculation (AMS-III.AV §5.3 Eq. 1, with §5.4 Eq. 4 SEC)

```
BEy = QPW(L) × m × Xboil × SEC × Σ_i (BLfuel_i × f_i × EF_fuel_i × 10^-9)

SEC = (WH × (Tf − Ti) + Xboil × WHE) / η_wb

PEy = E_el × EF_grid × (1 + L_TD) + Σ project fossil fuel use × EF_fossil
ERy = BEy − PEy − Lky
vcuQuantity = ERy × (1 − bufferPct)
bufferVcuQuantity = ERy × bufferPct
```

### Three policy gates

1. **VVB DID independence** — Validation report's signing DID must differ from Verification report's signing DID.
2. **Water Quality hard gate** — Issuance is rejected unless a Water Quality Survey VC contains `microSafetyConfirmed = true` with method and date.
3. **Anti-tampering check** — `vcuQuantity + bufferVcuQuantity` must equal `ERy` exactly (deterministic equality).

### Configurable parameters

- Buffer percentage: 5–30%, default 10% (per Verra SDW practice)
- Crediting period: maximum 21 years (VMR0015 cap)
- Owner Confirmation freshness: 24h vs `monitoringPeriodEnd`
- VVB signature freshness: 30 days

## Files in this submission

- `AMS-III.AV_VMR0015_v6.9.0.policy` — Guardian policy file (186 KB, 143 blocks, version 1.1.1)
- `schemas/01-Project-Registration_v1.1.0.schema` through `12-Population-Census_v1.1.5.schema` — 12 published schemas, exported from Guardian as zip archives containing `<iri>.json`, `ipfs/<iri>.document`, `ipfs/<iri>.context`
- `test-data/TC1_golden_fixture.json` — TC1 input fixture and expected outputs
- `test-data/TC1_full_lifecycle.record` — MGS record file capturing the full Project Registration → Annual Monitoring → Water Quality → VVB Validation → VVB Verification → Owner Confirmation → mint lifecycle
- `Hedera-Testnet-Evidence.md` — On-chain proofs (HCS topic IDs, message sequence numbers, IPFS CIDs)
- `Technical-Whitepaper.md` — calc engine derivation, gate logic, audit trail design

## Schema list

| # | Name | UUID | Version | Role |
|---|---|---|---|---|
| 01 | Project Registration | `18bf0b02-27d1-4551-ac92-f0c03718620c` | 1.1.0 | Required for project entry |
| 02 | Annual Monitoring | `f23a8b6b-1334-4e8b-96b5-05923eeed8d5` | 1.1.0 | Submitted each monitoring period |
| 03 | Water Quality Survey | `88a1a1e1-de73-4a77-b63d-1a1a493a8933` | 1.1.0 | Microbiological safety attestation |
| 04 | VVB Accreditation | `93d7d31e-aeca-4dc3-9945-8e153a6442bc` | 1.1.0 | VVB onboarding |
| 05 | VVB Validation Report | `d415d74f-12b3-45d4-88d0-bcce8a8ebf47` | 1.1.6 | First VVB sign-off |
| 06 | VVB Verification Report | `51bfeb38-3e08-4b7c-9dc4-f942e2a0f2a0` | 1.1.6 | Second VVB sign-off (independent DID) |
| 07 | Owner Confirmation | `7889d742-09d1-473c-aa76-0b92262e7d9f` | 1.1.0 | Standard Registry final approval |
| 08 | Calculation Results | `4bb9c304-1bd0-49d7-81c8-ece542ae5a58` | 1.1.0 | Calc engine output VC |
| 09 | Issuance Metadata | `f8033bcc-32f6-4fc0-a273-30ceca126497` | 1.1.5 | Mint metadata |
| 10 | Retirement Request | `a51367fa-9221-4b48-8d89-2d21495a08c3` | 1.1.5 | Retirement workflow |
| 11 | HCS Telemetry | `3d64e733-1012-4b9f-936d-ab7fce8abd57` | 1.1.5 | Optional supplementary telemetry |
| 12 | Population Census | `34f221fd-62b1-4a30-9a8b-a22331f063c6` | 1.1.5 | Optional supplementary household data |

## Test fixture (TC1) — expected outputs

Inputs:
- `QPW(L)` = 14400 L/period, `m` = 1.0, `Xboil` = 0.005
- Fuel mix: 100% non-renewable biomass, `EF_fuel` = 112000 kg CO₂/TJ, `f_NRB` = 0.85
- WH = 4.186, Tf = 100, Ti = 20, WHE = 2260, η_wb = 0.20
- E_el = 7500 kWh, EF_grid = 0.0008 tCO₂/kWh, L_TD = 0
- bufferPct = 0.10

Expected outputs (calc engine version `v6.9.0`):
- `BEy` = 12,251.555 tCO₂e
- `PEy` = 6.000 tCO₂e
- `Lky` = 0
- `ERy` = 12,245.555 tCO₂e
- `vcuQuantity` = 11,020.999
- `bufferVcuQuantity` = 1,224.555

These reproduce on any Node 20+ runtime.

## On-chain evidence

The policy and schemas are published on Hedera testnet. See `Hedera-Testnet-Evidence.md` for full proofs. Summary:

- **Policy schema topic**: [`0.0.8846119`](https://hashscan.io/testnet/topic/0.0.8846119) — 12 schemas published
- **Policy instance topic**: [`0.0.8855568`](https://hashscan.io/testnet/topic/0.0.8855568) — created at policy publish
- **Policy publish timestamp**: `1777937982.884334127` (sequence 21 on schema topic)
- **Schema package IPFS CID**: `QmNN9xUAbTXTJCgB7RPoikaLvQMtbbfotgyqGrAyA5AF7y`
- **Token ID**: `0.0.8850715` (ER-SDW)
- **Owner DID**: `did:hedera:testnet:FTYpFSq3k8DSeRK65zYYHtngcf4iChkzn8KTq3Qnq9cD_0.0.8845725`

## Limitations and open items

The following are known limitations of this submission, listed for reviewer awareness:

- **Testnet only** — Policy is published on Hedera testnet, not mainnet. No real credits have been issued.
- **No registered project** — TC1 is a synthetic fixture. No real safe drinking water project is currently registered against this policy.
- **VVB pool not onboarded** — DID independence is enforced in code, but no accredited VVB DIDs are currently registered. The policy is ready to accept them; outreach has not been completed.
- **Custodial signing** — Standard Registry signing key is held by the MGS instance operator, not by an independent registry entity.
- **Default leakage = 0** — `Lky` defaults to zero; projects must override with `reg.leakageOverrideTCO2e` if site-specific leakage is non-zero. Methodology guidance allows zero default for systems where avoided fuel is genuinely consumed off-site, but this should be reviewer-checked per project.
- **fNRB defaults** — If `fNRB` is not provided in Project Registration, the calc engine uses 0.85 (CDM TOOL30 default for India non-renewable biomass). Other regions should provide site-specific values.

## License

Apache-2.0, as required by the bounty program.

## Author

Bikram Biswas — biswasbikram786016@gmail.com — [@BikramBiswas786](https://github.com/BikramBiswas786)

Kolkata, West Bengal, India.
