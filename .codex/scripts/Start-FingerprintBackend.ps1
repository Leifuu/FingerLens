#requires -Version 7.0
[CmdletBinding()] param(
    [ValidateSet('Start','Stop','Restart','Inspect')][string]$Action = 'Start',
    [ValidateRange(1024,65535)][int]$Port = 8765,
    [string]$ProjectRoot = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent),
    [string]$PythonPath = 'C:\Program Files\Python312\python.exe',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$ErrorActionPreference = 'Stop'
$script = Join-Path $ProjectRoot 'backend\fingerprint_backend.py'
$listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
if ($Action -eq 'Inspect') { $listeners | Select-Object LocalAddress,LocalPort,OwningProcess; return }
if ($Action -in 'Stop','Restart') {
    foreach ($processId in ($listeners.OwningProcess | Sort-Object -Unique)) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
        if (-not $process.CommandLine.Contains($script)) { throw "端口进程不属于本工程，拒绝终止 $processId" }
        Stop-Process -Id $processId
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        if ([DateTime]::UtcNow -ge $deadline) { throw '后端端口尚未释放' }
        Start-Sleep -Milliseconds 100
    }
    if ($Action -eq 'Stop') { return }
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $Port 已被占用，拒绝重复启动" }
foreach ($path in @($PythonPath,$script,$AdbPath)) {
    if (-not (Test-Path -LiteralPath $path) -or $path -match '["\r\n&|<>^%]') { throw '服务路径无效或包含 cmd 元字符' }
}
$command = '""{0}" -B "{1}" --port {2} --adb "{3}""' -f $PythonPath,$script,$Port,$AdbPath
Start-Process -FilePath "$env:WINDIR\System32\cmd.exe" -ArgumentList @('/k',$command) -WorkingDirectory $ProjectRoot -WindowStyle Normal | Out-Null
Write-Output "已请求在可见 cmd 中启动 FingerLens backend: http://127.0.0.1:$Port"
