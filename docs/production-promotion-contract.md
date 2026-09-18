# FUNCTIONS production promotion review

The `production-contract` job in [ci.yml](../.github/workflows/ci.yml) runs only for a push to `main`, after `verify`, and only when `vars.FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED == 'true'`. It uses the protected `production` environment and read-only repository/artifact permissions. The existing develop-only staging job is unchanged. This change does not set any gate, assume an AWS role, or launch production.

The job downloads one explicitly reviewed public GitHub Actions artifact, then executes [check-production-promotion.ps1](../scripts/check-production-promotion.ps1). Valid inputs return `PRODUCTION_CONTRACT_VALIDATED_DEPLOYMENT_DISABLED`. An optional, separately gated runtime preflight validates the real production Terraform entrypoint without executing Terraform. The remote `start-deploy.ps1` launcher still rejects production: transporting the reviewed bundle through the K8S executor is a separate integration prerequisite.

## Reviewed variables

| Variable | Required review |
| --- | --- |
| `FUNCTIONS_PRODUCTION_DEPLOYMENT_ENABLED` | Exact `true` selects the main-only contract job. Leave unset/false by default. Configure as a repository/organization variable under administrator control: environment-only variables are unavailable when selecting a job. |
| `FUNCTIONS_PRODUCTION_ROLE_ARN` | Exact reviewed same-account production role ARN. No role assumption occurs. |
| `FUNCTIONS_PRODUCTION_INPUTS_RUN_ID` / `FUNCTIONS_PRODUCTION_INPUTS_ARTIFACT_ID` | Explicit numeric IDs of one reviewed, nonexpired public input artifact in this repository. No latest/name fallback. |
| `FUNCTIONS_PRODUCTION_INPUTS_SHA256` | Independently reviewed hash of `production-inputs.json` inside that artifact; never minted from downloaded bytes by the workflow. |
| `FUNCTIONS_PRODUCTION_LAUNCHER_ENABLED` | Exact `true` selects the optional private executor preflight; leave unset/false by default. The workflow never supplies `-ApplyReviewedPlan` and never obtains production AWS/OIDC identity. |

Store role/input variables in the protected production environment. Administrators must separately verify main-only environment branch rules, required reviewers and IAM trust. YAML and local tests do not establish these external protections.

## Public artifact and staging receipt

The artifact must contain `production-inputs.json` with numeric `schemaVersion: 1` and scalar strings `environment: production`, `sourceCommit` (the exact `github.sha`), `accountId`, `roleArn`, `artifactBucket`, `stateBucket`, `projectName: oficina-phase3-oficina-functions-production-deploy`, `sourcePrefix: releases/functions/production`, and immutable `deployerImageDigest` (`sha256:<64 lowercase hex digits>`).

Fields `sourceArchive`, `releaseManifest`, `terraformVariables`, `cloudWindowEvidence` and `stagingPromotion` each contain `path` (relative to the input JSON directory, without escaping it) and independently reviewed `sha256`. Include every referenced file in the artifact. No credential values or Terraform state belong in the public bundle.

`stagingPromotion` uses the existing `staging-deployment-result.json` shape written by `start-deploy.ps1`: numeric schema 1, staging environment, same `sourceCommit` and source `artifactSha256`, `status: SUCCEEDED`, a staging CodeBuild `buildId`, and nonfuture UTC `recordedAtUtc`. A dry-run result or another commit/build project is rejected. Review the actual successful staging result and its provenance before approving its hash; the offline validator does not query CodeBuild or authenticate operator-supplied metadata. The existing staging result is not automatically published by this change: assembling/publishing the reviewed public input artifact remains a separate prerequisite.

The production manifest binds the same commit/archive, boolean `promotedFromStaging: true`, `stagingArtifactSha256`, deployer, reviewed Terraform configuration/window hashes, contract `phase3-v2` and migration `V8`. Terraform inputs must target production in the reviewed account/us-east-1 and contain an immutable Lambda object version with consistent hex/base64 hashes. Older staging results bind the source archive only and remain insufficient for runtime execution.

New successful staging results additionally contain `runtimeBinding`: `sourceVersionId`, `releaseManifestVersionId`, `terraformVariablesVersionId`, `releaseManifestSha256`, `terraformVariablesSha256`, `deployerImageDigest`, and `lambda_artifact` copied from the exact uploaded configuration. That object includes `s3_bucket`, `s3_key`, `s3_object_version`, `sha256_hex` and `sha256_base64`. Production execution requires all five Lambda fields and the deployer image digest to equal the successful staging binding. This promotes the identical versioned JAR; it does not rebuild or copy a new artifact. Dry-run/failed results cannot supply successful runtime evidence. This repository uses JAR-based Lambda functions; the container-image binding is the executor image, not a nonexistent Lambda container image.

The exact commit needs prior successful staging evidence; a merge with a different SHA cannot reuse another commit's receipt, even if trees match. Production requires a numeric-budget cloud window; study-staging billing acknowledgment is insufficient.

## Local verification and pending acceptance

[deploy-production.ps1](../scripts/deploy-production.ps1) requires the reviewed inputs, main/push context, `-ProtectedEnvironment production`, exact `-Enabled true` and an explicit ownership handoff (`ownership_handoff_reviewed: true`). It checks the existing `infra/environments/production` root. Default execution returns `PRODUCTION_RUNTIME_VALIDATED_DEPLOYMENT_DISABLED`; only `-LauncherEnabled true` together with `-ApplyReviewedPlan` and no `-DryRun` executes the private adapter. These context strings do not independently verify GitHub protections or assumed IAM identity; the reviewed private executor must preserve that boundary.

The adapter revalidates all file hashes, runtime bindings and the production budget window before each external step, holds the shared deployment lock, initializes the real production root with its read-only provider lock, validates, creates a unique saved plan and applies that exact plan. Terraform output stays restricted; logs contain only the failing operation. Failures prevent subsequent steps, clean the local plan and release only the owned lock. A successful apply reports infrastructure application only; runtime health, receipt publication and R4 acceptance remain separate.

The platform-compatible [deploy.ps1](../scripts/deploy.ps1) entrypoint accepts `ProductionEnabled`, `ProductionLauncherEnabled`, `ProtectedEnvironment`, `ProductionRoleArn`, `ProductionInputsFile`, `ExpectedProductionInputsSha256`, `EventName` and `BranchRef`. It also binds existing source/manifest/deployer/tfvars hash parameters to the review, requires the shared lock bucket, and enforces `/tmp/oficina/functions_production.tfvars.json`, `functions/production.tfstate`, its `.tflock`, and `us-east-1`. It forwards the explicit apply switch without changing the staging path.

`tests/production-promotion-contract.ps1` covers branch/event isolation, default/missing gates, invalid/absent receipts, scalar metadata types, exact source/JAR/image bindings, altered bytes, valid-but-disabled behavior, the real executor wrapper, lock/plan/window failures and a staging-result-to-production round trip. AWS and Terraform commands are mocked. `tests/verify-infrastructure.ps1` includes it alongside existing mocked infrastructure checks. No production AWS/OIDC or real Terraform apply occurs during these checks.

This contract is source evidence only. Existing staging receipts retain their scope, and [R4 status](evidence/r4-local-status.json) remains unchanged. A new genuine same-commit staging receipt with runtime bindings, reviewed production artifact/configuration, completed production ownership handoff, verified protections/identity, production cost authorization, and K8S-owned production CodeBuild bundle transport remain pending. No gate, secret, IAM policy or live setting was changed.
