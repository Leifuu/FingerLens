#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Build','TestAndroid','Install','Run','Capture','Inspect','StartWeb','StopWeb','CheckEncoding')][string]$Action = 'Inspect',
    [string]$ProjectRoot = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent),
    [string]$DeviceSerial = '127.0.0.1:53444',
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$JavaPath = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
    [string]$NodePath = 'C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe',
    [string]$NpmCliPath = 'C:\nvm4w\nodejs\node_modules\npm\bin\npm-cli.js',
    [ValidateRange(1024,65535)][int]$Port = 5173,
    [ValidateRange(10,240)][int]$TimeoutSeconds = 200,
    [string]$OutputDirectory,
    [switch]$Gzip,
    [switch]$PreparePermissions
)
$ErrorActionPreference = 'Stop'
$package = 'com.life.fingerprint'
$ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$taskTmp = Join-Path $ProjectRoot '.codex\tmp'
New-Item -ItemType Directory -Path $taskTmp -Force | Out-Null
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "命令执行失败（$LASTEXITCODE）：$Executable" }
}
function Assert-Device {
    if ($DeviceSerial -notmatch '^[A-Za-z0-9_.:\[\]-]{1,200}$') { throw '设备 serial 格式非法' }
    $state = & $AdbPath -s $DeviceSerial get-state 2>&1
    if ($LASTEXITCODE -ne 0 -or ($state -join '').Trim() -ne 'device') { throw "明确目标不在线：$DeviceSerial；不会自动切换设备" }
}
function Get-Control([string]$Label) {
    $remote = '/sdcard/fingerprint-ui.xml'
    Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','uiautomator','dump',$remote) | Out-Null
    $local = Join-Path $taskTmp 'fingerprint-ui.xml'
    Invoke-Checked $AdbPath @('-s',$DeviceSerial,'pull',$remote,$local) | Out-Null
    [xml]$xml = Get-Content -LiteralPath $local -Encoding UTF8 -Raw
    $node = $xml.SelectNodes('//node') | Where-Object { $_.text -eq $Label } | Select-Object -First 1
    if (-not $node -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw "未找到界面控件：$Label" }
    $centerX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $centerY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    return @($centerX,$centerY)
}
function Start-App {
    Assert-Device
    Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','am','force-stop',$package)
    Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','am','start','-W','-n',"$package/.MainActivity")
}
switch ($Action) {
    'Build' {
        Push-Location (Join-Path $ProjectRoot 'app')
        try { Invoke-Checked $JavaPath @('-classpath','gradle\wrapper\gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':app:assembleRelease',':app:lintRelease','--console=plain') } finally { Pop-Location }
        Push-Location (Join-Path $ProjectRoot 'ant-frontend')
        $oldPath = $env:PATH
        try { $env:PATH = (Split-Path $NodePath -Parent) + ';' + $oldPath; Invoke-Checked $NodePath @($NpmCliPath,'run','test'); Invoke-Checked $NodePath @($NpmCliPath,'run','build') } finally { $env:PATH = $oldPath; Pop-Location }
    }
    'TestAndroid' {
        Assert-Device
        $releaseApk = Join-Path $ProjectRoot 'app\app\build\outputs\apk\release\app-release.apk'
        if (-not (Test-Path -LiteralPath $releaseApk)) { throw '请先 Build，确保测试后可恢复 release' }
        Push-Location (Join-Path $ProjectRoot 'app')
        try { Invoke-Checked $JavaPath @('-classpath','gradle\wrapper\gradle-wrapper.jar','org.gradle.wrapper.GradleWrapperMain',':app:assembleDebug',':app:assembleDebugAndroidTest','--console=plain') } finally { Pop-Location }
        try {
            Invoke-Checked $AdbPath @('-s',$DeviceSerial,'install','-r',(Join-Path $ProjectRoot 'app\app\build\outputs\apk\debug\app-debug.apk'))
            Invoke-Checked $AdbPath @('-s',$DeviceSerial,'install','-r',(Join-Path $ProjectRoot 'app\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'))
            $result = & $AdbPath -s $DeviceSerial shell am instrument -w -r -e class com.life.fingerprint.CollectionChecks com.life.fingerprint.test/androidx.test.runner.AndroidJUnitRunner 2>&1
            $exitCode = $LASTEXITCODE
            $text = $result -join "`n"
            $testLog = Join-Path $taskTmp ('android-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff') + '.txt')
            [IO.File]::WriteAllText($testLog,$text,[Text.UTF8Encoding]::new($false))
            Write-Output $text
            if ($exitCode -ne 0 -or $text -notmatch 'OK \(\d+ tests?\)' -or $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED') { throw "仪器测试未通过，见 $testLog" }
        } finally {
            & $AdbPath -s $DeviceSerial uninstall "$package.test"
            if ($LASTEXITCODE -ne 0) { Write-Warning '测试 APK 未删除，请检查设备状态；仍会尝试恢复 release' }
            Invoke-Checked $AdbPath @('-s',$DeviceSerial,'install','-r',$releaseApk)
            Start-App
        }
    }
    'Install' { Assert-Device; Invoke-Checked $AdbPath @('-s',$DeviceSerial,'install','-r',(Join-Path $ProjectRoot 'app\app\build\outputs\apk\release\app-release.apk')); Start-App }
    'Run' { Start-App }
    'Inspect' { Assert-Device; Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','dumpsys','package',$package); Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress,LocalPort,OwningProcess }
    'Capture' {
        Assert-Device
        if (-not $OutputDirectory) { $OutputDirectory = Join-Path $taskTmp ('capture-' + ($DeviceSerial -replace '[^A-Za-z0-9]','_') + '-' + (Get-Date -Format 'yyyyMMdd-HHmmssfff')) }
        if (Test-Path -LiteralPath $OutputDirectory) { throw '输出目录已存在，拒绝覆盖' }
        New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
        Start-App
        if ($PreparePermissions) { $point = Get-Control '1. 准备采集权限'; Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','input','tap',[string][int]$point[0],[string][int]$point[1]); Write-Output '请在设备上处理权限弹窗后重新运行 Capture；本次尚未采集。'; break }
        $remoteDir = "/sdcard/Android/data/$package/files/exports"
        $existingNames = @(& $AdbPath -s $DeviceSerial shell ls $remoteDir 2>$null)
        $point = Get-Control '开始采集'
        Invoke-Checked $AdbPath @('-s',$DeviceSerial,'shell','input','tap',[string][int]$point[0],[string][int]$point[1])
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        $complete = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            $names = & $AdbPath -s $DeviceSerial shell ls -t $remoteDir 2>$null
            $name = $names | Where-Object { $_ -match '^fingerprint-[a-f0-9-]{36}\.json$' -and $_ -notin $existingNames } | Select-Object -First 1
            if ($name) {
                $candidate = Join-Path $OutputDirectory 'fingerprint.json'
                & $AdbPath -s $DeviceSerial pull "$remoteDir/$name" $candidate 2>$null | Out-Null
                if ($LASTEXITCODE -eq 0) { try { $report = Get-Content -LiteralPath $candidate -Encoding UTF8 -Raw | ConvertFrom-Json -Depth 100; if ($report.data.state -ne 'running') { $complete = $true; break } } catch {} }
            }
            Start-Sleep -Seconds 2
        }
        if (-not $complete) { throw "等待采集完成超时；已有原始结果保留在 $OutputDirectory" }
        if ($Gzip) { $source = [IO.File]::OpenRead($candidate); $target = [IO.File]::Create("$candidate.gz"); try { $zipStream = [IO.Compression.GZipStream]::new($target,[IO.Compression.CompressionLevel]::Optimal); try { $source.CopyTo($zipStream) } finally { $zipStream.Dispose() } } finally { $source.Dispose(); $target.Dispose() } }
        $apk = Join-Path $ProjectRoot 'app\app\build\outputs\apk\release\app-release.apk'
        $verification = [ordered]@{ sourceKind='apk-export'; deviceSerial=$DeviceSerial; collectedAt=(Get-Date -Format o); state=$report.data.state; sessionId=$report.data.sessionId; uid=$report.data.app.uid; debuggable=$report.data.app.debuggable; items=$report.data.items.Count; apkSha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant(); fingerprintSha256=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant(); limitation='ADB仅操作与导出；指纹值由APK产生，未混入shell设备属性。' }
        [IO.File]::WriteAllText((Join-Path $OutputDirectory 'verification.json'),($verification | ConvertTo-Json -Depth 10 -Compress),[Text.UTF8Encoding]::new($false))
        $verification | ConvertTo-Json -Depth 10
    }
    'StartWeb' {
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($listeners.Count) { throw "端口 $Port 已被占用，拒绝启动重复服务；请核对进程后 StopWeb" }
        $vite = Join-Path $ProjectRoot 'ant-frontend\node_modules\vite\bin\vite.js'
        foreach ($argument in @($NodePath,$vite)) { if ($argument -match '["\r\n&|<>^%]') { throw '服务路径包含不支持的 cmd 元字符' } }
        $command = '""{0}" "{1}" --host 127.0.0.1 --port {2} --strictPort"' -f $NodePath,$vite,$Port
        Start-Process -FilePath "$env:WINDIR\System32\cmd.exe" -ArgumentList @('/k',$command) -WorkingDirectory (Join-Path $ProjectRoot 'ant-frontend') -WindowStyle Normal | Out-Null
        Write-Output "已请求在可见 cmd 中启动 http://127.0.0.1:$Port"
    }
    'StopWeb' {
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        foreach ($processId in ($listeners.OwningProcess | Sort-Object -Unique)) {
            $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
            $expected = Join-Path $ProjectRoot 'ant-frontend\node_modules\vite\bin\vite.js'
            if (-not $process.CommandLine.Contains($expected)) { throw "端口所属进程不是本项目 Vite，拒绝终止 PID $processId" }
            Stop-Process -Id $processId
            Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
        }
        # 进程退出与端口释放存在短暂间隔；确认端口释放后才允许紧接着 StartWeb。
        $releaseDeadline = [DateTime]::UtcNow.AddSeconds(10)
        while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
            if ([DateTime]::UtcNow -ge $releaseDeadline) { throw "端口 $Port 尚未释放，请核对监听进程" }
            Start-Sleep -Milliseconds 100
        }
    }
    'CheckEncoding' {
        $bad = @()
        $files = Get-ChildItem -LiteralPath $ProjectRoot -Recurse -File | Where-Object { $_.FullName -notmatch '[\\/](node_modules|build|dist|\.gradle|\.idea|\.cxx|\.kotlin|tmp)[\\/]' -and $_.Extension -in '.java','.cpp','.txt','.xml','.json','.ts','.tsx','.css','.html','.md','.ps1','.mjs','.kts','.toml','.properties' }
        $strict = [Text.UTF8Encoding]::new($false,$true)
        foreach ($file in $files) { try { $bytes = [IO.File]::ReadAllBytes($file.FullName); if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) { throw 'BOM' }; $null = $strict.GetString($bytes) } catch { $bad += $file.FullName } }
        if ($bad.Count) { throw ('编码检查失败：' + ($bad -join ', ')) }
        Write-Output "UTF-8无BOM检查通过：$($files.Count) 个文件"
    }
}
