# Technical Whitepaper — AMS-III.AV v9.0 + VMR0015 v1.0 (v6.9.0)

This document explains the design of the v6.9.0 Safe Drinking Water dMRV policy: how the calc engine derives emission reductions, how the three policy gates are wired into the workflow, and how the audit trail is anchored to Hedera HCS and IPFS.

## 1. Methodology mapping

The policy combines two source documents:

- **AMS-III.AV v9.0** — UNFCCC CDM small-scale methodology, "Low greenhouse gas emitting safe drinking water production systems."
- **VMR0015 v1.0** — Verra revision that overrides VVB and crediting-period rules.

Each source clause is mapped 1:1 to a calc-engine variable or a workflow gate. The mapping table is embedded in the policy `meta.methodology_map` block.

| Source clause | Implementation |
|---|---|
| AMS-III.AV §5.3 Eq. 1 (BEy) | `calcBaselineEmissions()` in calc-engine block |
| AMS-III.AV §5.4 Eq. 4 (SEC) | `calcSEC()` |
| AMS-III.AV §5.5 (PEy) | `calcProjectEmissions()` |
| AMS-III.AV §5.6 (Lky) | `reg.leakageOverrideTCO2e` (defaults to 0) |
| VMR0015 §4.2 (VVB independence) | DID-independence gate |
| VMR0015 §4.3 (Crediting period cap) | `policy.maxCreditingYears = 21` |
| AMS-III.AV §A.4 (Water safety) | Water Quality hard gate |

## 2. Calc engine derivation

### 2.1 Baseline emissions (BEy)

```
BEy = QPW(L) × m × Xboil × SEC × Σ_i ( BLfuel_i × f_i × EF_fuel_i × 10^-9 )
```

Where:
- `QPW(L)` = volume of safe water supplied during period y (L)
- `m` = number of households served, normalized
- `Xboil` = fraction of baseline water that would have been boiled (default 0.005 ≈ 5L per 1000L of water; project-specific)
- `SEC` = specific energy consumption per litre (MJ/L), derived from §5.4 Eq. 4
- `BLfuel_i` = baseline-fuel share i (dimensionless, 0–1)
- `f_i` = non-renewability fraction of fuel i (`fNRB` for biomass; 1 for fossil)
- `EF_fuel_i` = emission factor in kgCO₂/TJ
- `10^-9` converts MJ × kg/TJ to tCO₂

### 2.2 Specific energy consumption (SEC)

```
SEC = ( WH × (Tf − Ti) + Xboil × WHE ) / η_wb
```

- `WH` = specific heat of water (4.186 kJ/kg·K)
- `Tf − Ti` = temperature rise (K)
- `WHE` = water heat of vaporization (2260 kJ/kg)
- `η_wb` = water-boiling efficiency (default 0.20 for traditional three-stone fires per CDM TOOL30)

### 2.3 Project emissions (PEy)

```
PEy = E_el × EF_grid × (1 + L_TD) + Σ ( fossil_i × EF_fossil_i )
```

- `E_el` = grid electricity consumed by the project (kWh)
- `EF_grid` = combined-margin grid emission factor (tCO₂/kWh)
- `L_TD` = transmission and distribution loss factor (dimensionless)

### 2.4 Emission reductions and VCU split

```
ERy = BEy − PEy − Lky
vcuQuantity = ERy × (1 − bufferPct)
bufferVcuQuantity = ERy × bufferPct
```

`bufferPct` is configurable per project, range 5–30%, default 0.10.

### 2.5 TC1 worked example

| Variable | Value |
|---|---|
| QPW(L) | 14,400 L |
| m | 1.0 |
| Xboil | 0.005 |
| Fuel mix | 100% non-renewable biomass |
| EF_fuel | 112,000 kg CO₂/TJ |
| fNRB | 0.85 |
| WH, Tf, Ti, WHE, η_wb | 4.186, 100, 20, 2260, 0.20 |
| E_el | 7,500 kWh |
| EF_grid | 0.0008 tCO₂/kWh |
| L_TD | 0 |
| bufferPct | 0.10 |

Calc-engine output:

| Variable | Value |
|---|---|
| BEy | 12,251.555 tCO₂e |
| PEy | 6.000 tCO₂e |
| Lky | 0 |
| ERy | 12,245.555 tCO₂e |
| vcuQuantity | 11,020.999 |
| bufferVcuQuantity | 1,224.555 |

Determinism: results are bit-identical across Node 20+ runs because all arithmetic uses `decimal.js` with `precision = 28, rounding = ROUND_HALF_EVEN`.

## 3. Policy gates

The v6.9.0 policy enforces three gates inside the policy graph (not as side-channel checks) so that violations stop the workflow before any token mint.

### 3.1 VVB DID independence

- Two VVB Reports are required: Validation (schema 05) and Verification (schema 06).
- A `documentValidatorBlock` reads `validationReport.signedBy.id` and `verificationReport.signedBy.id` from the prior VCs.
- A `customLogicBlock` returns `false` if both DIDs are equal. `false` routes the document to the `vvb_did_collision` rejection lane.
- Implements VMR0015 §4.2.

### 3.2 Water Quality hard gate

- Before the calc-engine runs, the policy fetches the most recent Water Quality Survey VC bound to the same project.
- Required fields: `microSafetyConfirmed = true`, `testMethod` (string, non-empty), `testDate` (ISO 8601, within `policy.maxWaterQualityAgeDays` days of `monitoringPeriodEnd`, default 180).
- Missing or stale survey ⇒ workflow halts at `wq_gate_fail`. No calc-engine call, no mint.
- Implements AMS-III.AV §A.4.

### 3.3 Anti-tampering check

- After the calc-engine emits the Calculation Results VC, a final `customLogicBlock` re-reads `vcuQuantity`, `bufferVcuQuantity`, and `ERy`.
- It asserts `Decimal(vcuQuantity).plus(bufferVcuQuantity).eq(Decimal(ERy))` exactly.
- Discrepancy ⇒ `tamper_detected` lane. The mint block is downstream of this check.
- This catches schema injection or mid-flight VC mutation between calc engine and mint.

## 4. Audit trail design

### 4.1 Layered anchoring

| Layer | Anchored where | Verifiable via |
|---|---|---|
| Schema definitions | HCS topic `0.0.8846119` + IPFS | Mirror Node REST + IPFS gateway |
| Policy publish event | HCS topic `0.0.8846119` (sequence 21) | Mirror Node `/topics/0.0.8846119/messages/21` |
| Per-project lifecycle messages | Per-instance topic `0.0.8855568` | Mirror Node `/topics/0.0.8855568/messages` |
| Token mint and transfer | Hedera Token Service (token `0.0.8850715`) | Mirror Node `/tokens/0.0.8850715` |
| Retirement events | HCS topic + token burn record | Mirror Node |

### 4.2 Per-VC anchoring

Every VC issued by the policy (Project Registration, Annual Monitoring, Water Quality, Validation, Verification, Owner Confirmation, Calculation Results, Issuance Metadata, Retirement Request) is:

1. Hashed with SHA-256 over the canonicalized JSON payload.
2. The hash is included in an HCS message on `0.0.8855568`.
3. The full JSON is pushed to IPFS; the CID is included in the same HCS message.

A reviewer can therefore reconstruct the entire project lifecycle from public Mirror Node and IPFS reads only — no Guardian / MGS server is required.

### 4.3 Reproducibility

To re-derive any TC outcome from raw evidence:

```bash
# Pull the policy
curl "https://testnet.mirrornode.hedera.com/api/v1/topics/0.0.8846119/messages/21"

# Pull schema package
curl "https://ipfs.io/ipfs/QmNN9xUAbTXTJCgB7RPoikaLvQMtbbfotgyqGrAyA5AF7y"

# Pull lifecycle messages for a specific project
curl "https://testnet.mirrornode.hedera.com/api/v1/topics/0.0.8855568/messages?limit=200&order=asc"

# For each consensus message: dereference the IPFS CID to get the VC, hash-verify
```

The TC1 fixture in `test-data/TC1_golden_fixture.json` is the canonical input for re-running the calc engine offline against the policy file.

## 5. Schema bindings

The policy binds 12 schemas at fixed UUID + version pairs. These are encoded in the policy JSON's block `schemas` array and are immutable for the lifetime of the policy version.

| # | Name | UUID | Version |
|---|---|---|---|
| 01 | Project Registration | `18bf0b02-27d1-4551-ac92-f0c03718620c` | 1.1.0 |
| 02 | Annual Monitoring | `f23a8b6b-1334-4e8b-96b5-05923eeed8d5` | 1.1.0 |
| 03 | Water Quality Survey | `88a1a1e1-de73-4a77-b63d-1a1a493a8933` | 1.1.0 |
| 04 | VVB Accreditation | `93d7d31e-aeca-4dc3-9945-8e153a6442bc` | 1.1.0 |
| 05 | VVB Validation Report | `d415d74f-12b3-45d4-88d0-bcce8a8ebf47` | 1.1.6 |
| 06 | VVB Verification Report | `51bfeb38-3e08-4b7c-9dc4-f942e2a0f2a0` | 1.1.6 |
| 07 | Owner Confirmation | `7889d742-09d1-473c-aa76-0b92262e7d9f` | 1.1.0 |
| 08 | Calculation Results | `4bb9c304-1bd0-49d7-81c8-ece542ae5a58` | 1.1.0 |
| 09 | Issuance Metadata | `f8033bcc-32f6-4fc0-a273-30ceca126497` | 1.1.5 |
| 10 | Retirement Request | `a51367fa-9221-4b48-8d89-2d21495a08c3` | 1.1.5 |
| 11 | HCS Telemetry | `3d64e733-1012-4b9f-936d-ab7fce8abd57` | 1.1.5 |
| 12 | Population Census | `34f221fd-62b1-4a30-9a8b-a22331f063c6` | 1.1.5 |

Newer 1.2.x versions of schemas 01, 02, 03 are also published on the schema topic but not bound in this policy version. They are reserved for v6.10.x.

## 6. Open items

These are intentional, transparent gaps the reviewer should be aware of:

1. The default `Lky = 0` is permissive. Site-specific leakage assessment is the project developer's responsibility under AMS-III.AV §5.6.
2. `fNRB = 0.85` default applies to India per CDM TOOL30; other geographies must override.
3. Owner Confirmation freshness is 24h relative to `monitoringPeriodEnd`. This is enforced in policy code but is not a methodology requirement; tighten or relax via `policy.config.ownerConfirmationFreshnessHours`.
4. The buffer percent is per-project and can be set as low as 5%. Verra SDW practice is 10% pooled or 20% non-permanent; the policy does not enforce a Verra default — the Standard Registry does.
5. No accredited VVB DIDs are currently registered. The DID-independence gate is implemented and unit-tested, but it has not been exercised against real third-party VVB DIDs.

## 7. Reproducing TC1

```bash
node calc-engine/run.js \
  --policy AMS-III.AV_VMR0015_v6.9.0.policy \
  --input test-data/TC1_golden_fixture.json \
  --expected test-data/TC1_full_lifecycle.record
```

The runner exits with `0` and prints a green "TC1 PASS" if and only if all six output values match the expected to 3 decimal places.

---

License: Apache-2.0.

Author: Bikram Biswas — biswasbikram786016@gmail.com — [@BikramBiswas786](https://github.com/BikramBiswas786).
