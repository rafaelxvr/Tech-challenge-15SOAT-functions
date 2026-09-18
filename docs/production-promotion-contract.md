# FUNCTIONS production promotion review

The `production-contract` job in [ci.yml](../.github/workflows/ci.yml) runs only for a push to `main`, after `verify`, and only when `vars.FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED == 'true'`. It uses the protected `production` environment and read-only repository/artifact permissions. The existing develop-only staging job is unchanged. This change does not set any gate, assume an AWS role, or launch production.

The job downloads one explicitly reviewed public GitHub Actions artifact, then executes [check-production-promotion.ps1](../scripts/check-production-promotion.ps1). Valid inputs return `PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED`. The current production prohibition in both launcher and executor remains intact.

## Reviewed variables

| Variable | Required review |
| --- | --- |
| `FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED` | Exact `true` selects the main-only contract job. Leave unset/false by default. Configure as a repository/organization variable under administrator control: environment-only variables are unavailable when selecting a job. |
| `FUNCTIONS_PRODUCTION_ROLE_ARN` | Exact reviewed same-account production role ARN. No role assumption occurs. |
| `FUNCTIONS_PRODUCTION_INPUTS_RUN_ID` / `FUNCTIONS_PRODUCTION_INPUTS_ARTIFACT_ID` | Explicit numeric IDs of one reviewed, nonexpired public input artifact in this repository. No latest/name fallback. |
| `FUNCTIONS_PRODUCTION_INPUTS_SHA256` | Independently reviewed hash of `production-inputs.json` inside that artifact; never minted from downloaded bytes by the workflow. |
| `FUNCTIONS_PRODUCTION_LAUNCHER_ENABLED` | Separate future execution gate, left unset/false. Setting it currently fails with `FUNCTIONS_PRODUCTION_LAUNCHER_NOT_IMPLEMENTED`; a separately reviewed production adapter is still required. |

Store role/input variables in the protected production environment. Administrators must separately verify main-only environment branch rules, required reviewers and IAM trust. YAML and local tests do not establish these external protections.

## Public artifact and staging receipt

The artifact must contain `production-inputs.json` with numeric `schemaVersion: 1` and scalar strings `environment: production`, `sourceCommit` (the exact `github.sha`), `accountId`, `roleArn`, `artifactBucket`, `stateBucket`, `projectName: oficina-phase3-oficina-functions-production-deploy`, `sourcePrefix: releases/functions/production`, and immutable `deployerImageDigest` (`sha256:<64 lowercase hex digits>`).

Fields `sourceArchive`, `releaseManifest`, `terraformVariables`, `cloudWindowEvidence` and `stagingPromotion` each contain `path` (relative to the input JSON directory, without escaping it) and independently reviewed `sha256`. Include every referenced file in the artifact. No credential values or Terraform state belong in the public bundle.

`stagingPromotion` uses the existing `staging-deployment-result.json` shape written by `start-deploy.ps1`: numeric schema 1, staging environment, same `sourceCommit` and source `artifactSha256`, `status: SUCCEEDED`, a staging CodeBuild `buildId`, and nonfuture UTC `recordedAtUtc`. A dry-run result or another commit/build project is rejected. Review the actual successful staging result and its provenance before approving its hash; the offline validator does not query CodeBuild or authenticate operator-supplied metadata. The existing staging result is not automatically published by this change: assembling/publishing the reviewed public input artifact remains a separate prerequisite.

The production manifest binds the same commit/archive, boolean `promotedFromStaging: true`, `stagingArtifactSha256`, deployer, reviewed Terraform configuration/window hashes, contract `phase3-v2` and migration `V8`. Terraform inputs must target production in the reviewed account/us-east-1 and contain an immutable Lambda object version with consistent hex/base64 hashes. This is reviewed production configuration, not proof that the current staging result records the same Lambda JAR: that result currently binds the source archive only. Runtime artifact promotion/provenance must be reviewed before a future production execution adapter is enabled.

The exact commit needs prior successful staging evidence; a merge with a different SHA cannot reuse another commit's receipt, even if trees match. Production requires a numeric-budget cloud window; study-staging billing acknowledgment is insufficient.

## Local verification and pending acceptance

`tests/production-promotion-contract.ps1` covers branch/event isolation, default/missing gates, invalid or absent receipts, scalar metadata types, same-commit/source binding, reviewed role/config, altered bytes and valid-but-disabled behavior. It also rejects attempted launcher enablement. `tests/verify-infrastructure.ps1` includes it alongside existing mocked infrastructure checks. No production AWS/OIDC or launcher is executed.

This contract is source evidence only. Existing staging receipts retain their scope, and [R4 status](evidence/r4-local-status.json) remains unchanged. Live protection verification, genuine same-commit staging receipt provenance, reviewed input artifact publication, production cost authorization and a separately reviewed production launcher remain pending.
