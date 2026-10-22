#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CacheDirectory,
    [string]$ProjectRoot = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
)
$ErrorActionPreference = 'Stop'
$reviewPath = Join-Path $CacheDirectory 'verification.json'
$rawPath = Join-Path $CacheDirectory 'roots-download.json'
$review = Get-Content -LiteralPath $reviewPath -Encoding UTF8 -Raw | ConvertFrom-Json -Depth 100
$source = $review.sources.roots
$digest = (Get-FileHash -LiteralPath $rawPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($source.url -ne 'https://android.googleapis.com/attestation/root' -or $source.httpStatus -ne 200 -or $source.sha256 -ne $digest) { throw '公开缓存 URL、HTTP 状态或摘要不匹配' }
if ((Get-Item -LiteralPath $rawPath).Length -gt 1MB) { throw '根快照超过1MiB' }
$certificates = @(Get-Content -LiteralPath $rawPath -Encoding UTF8 -Raw | ConvertFrom-Json)
if ($certificates.Count -lt 1 -or $certificates.Count -gt 32) { throw '根证书数量非法' }
foreach ($pem in $certificates) {
    if ($pem -notmatch '^-----BEGIN CERTIFICATE-----[\s\S]+-----END CERTIFICATE-----\s*$') { throw '根证书 PEM 格式不匹配' }
    $der = [Convert]::FromBase64String(($pem -replace '-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s',''))
    $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($der)
    $certificate.Dispose()
}
$target = Join-Path $ProjectRoot 'shared\trust-roots.json'
if (Test-Path -LiteralPath $target) { $backup = Join-Path $ProjectRoot ('.codex\backup-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff') + '-trust'); New-Item -ItemType Directory -Path $backup | Out-Null; Copy-Item -LiteralPath $target -Destination $backup }
$snapshot = [ordered]@{ source=$source.url; fetchedAt=$source.queriedAt; sha256=$digest; status='cached-public-response'; certificates=$certificates }
[IO.File]::WriteAllText($target,($snapshot | ConvertTo-Json -Depth 10 -Compress),[Text.UTF8Encoding]::new($false))
[pscustomobject]@{ file=$target; roots=$certificates.Count; fetchedAt=$source.queriedAt; sha256=$digest; limitation='使用明确日期的历史公开根快照，未声称本次联网更新或实时撤销检查' } | ConvertTo-Json -Compress
