[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('staging','production')][string]$Environment,
    [Parameter(Mandatory)][ValidatePattern('^[a-f0-9]{40}$')][string]$SourceCommit,
    [Parameter(Mandatory)][string]$TerraformOutputsJsonFile,
    [Parameter(Mandatory)][string]$OutputFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$contract = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../contracts/outputs-allowlist.json') -Raw | ConvertFrom-Json
$raw = Get-Content -LiteralPath $TerraformOutputsJsonFile -Raw | ConvertFrom-Json
$outputs = [ordered]@{}
foreach ($field in $contract.outputs.PSObject.Properties) {
    $item = $raw.PSObject.Properties[$field.Value]
    if ($null -eq $item -or $item.Value.sensitive -eq $true) { throw "Missing or sensitive allowlisted output: $($field.Name)" }
    if ($field.Name -ceq 'customerPublicKeys') {
        foreach ($publicKey in $item.Value.value.PSObject.Properties) {
            $names = @($publicKey.Value.PSObject.Properties.Name | Sort-Object)
            if (($names -join ',') -cne 'alg,e,kty,n,use' -or $publicKey.Value.kty -cne 'RSA' -or $publicKey.Value.alg -cne 'RS256' -or $publicKey.Value.use -cne 'sig' -or $publicKey.Value.e -cne 'AQAB' -or $publicKey.Value.n -cnotmatch '^[A-Za-z0-9_-]{342,684}$') { throw 'customerPublicKeys must contain only public RSA JWK fields.' }
        }
    }
    $outputs[$field.Name] = $item.Value.value
}
[ordered]@{ schemaVersion = 1; environment = $Environment; sourceCommit = $SourceCommit; outputs = $outputs } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputFile -NoNewline
