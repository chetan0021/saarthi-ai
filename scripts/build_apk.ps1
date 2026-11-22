[CmdletBinding()]
param(
    [ValidateSet("release", "debug")]
    [string]$Type = "release"
)

$ErrorActionPreference = "Stop"

# 1. Resolve paths
$ScriptDir = $PSScriptRoot
$RepoRoot = (Resolve-Path "$ScriptDir\..").Path
$AndroidDir = Join-Path $RepoRoot "android"
$DistDir = Join-Path $RepoRoot "dist"
$LocalPropsFile = Join-Path $AndroidDir "local.properties"
$BuildGradleFile = Join-Path $AndroidDir "app\build.gradle.kts"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Building Saarthi Android App (Type: $Type)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 2. Check local.properties and sdk.dir
if (-not (Test-Path $LocalPropsFile)) {
    Write-Host "`n[ERROR] Missing local.properties at: $LocalPropsFile" -ForegroundColor Red
    Write-Host "Please create android\local.properties with:" -ForegroundColor Yellow
    Write-Host "sdk.dir=C:\\Users\\$env:USERNAME\\AppData\\Local\\Android\\Sdk`n" -ForegroundColor Yellow
    exit 1
}

$sdkLine = Get-Content $LocalPropsFile | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
if (-not $sdkLine) {
    Write-Host "`n[ERROR] sdk.dir property not found in $LocalPropsFile" -ForegroundColor Red
    Write-Host "Please add the following line to android\local.properties:" -ForegroundColor Yellow
    Write-Host "sdk.dir=C:\\Users\\$env:USERNAME\\AppData\\Local\\Android\\Sdk`n" -ForegroundColor Yellow
    exit 1
}

$rawSdkDir = ($sdkLine -split '=', 2)[1].Trim()
$sdkDir = $rawSdkDir -replace '\\:', ':' -replace '\\\\', '\'

if (-not (Test-Path $sdkDir)) {
    Write-Host "`n[ERROR] sdk.dir points to a non-existent folder: $sdkDir" -ForegroundColor Red
    Write-Host "Please update android\local.properties with a valid Android SDK directory." -ForegroundColor Yellow
    exit 1
}

Write-Host "[OK] Android SDK: $sdkDir" -ForegroundColor Green
$env:ANDROID_HOME = $sdkDir

# Ensure JAVA_HOME is configured
if (-not $env:JAVA_HOME -or -not (Test-Path $env:JAVA_HOME)) {
    $jbrPath = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $jbrPath) {
        $env:JAVA_HOME = $jbrPath
        Write-Host "[OK] Using Android Studio JBR: $env:JAVA_HOME" -ForegroundColor Green
    }
}

# 3. Find newest build-tools in SDK for aapt / aapt2
$buildToolsBase = Join-Path $sdkDir "build-tools"
if (-not (Test-Path $buildToolsBase)) {
    Write-Host "`n[ERROR] build-tools folder not found in SDK: $buildToolsBase" -ForegroundColor Red
    exit 1
}

$newestBuildTools = Get-ChildItem -Path $buildToolsBase -Directory |
    Sort-Object {
        $clean = $_.Name -replace '[^\d\.]', ''
        if ($clean -match '^\d+(\.\d+)*$') { [System.Version]$clean } else { [System.Version]"0.0.0" }
    } -Descending |
    Select-Object -First 1

if (-not $newestBuildTools) {
    Write-Host "`n[ERROR] No build-tools version found under: $buildToolsBase" -ForegroundColor Red
    exit 1
}

$aaptExe = Join-Path $newestBuildTools.FullName "aapt.exe"
if (-not (Test-Path $aaptExe)) {
    $aaptExe = Join-Path $newestBuildTools.FullName "aapt2.exe"
}
if (-not (Test-Path $aaptExe)) {
    Write-Host "`n[ERROR] Neither aapt.exe nor aapt2.exe found in $($newestBuildTools.FullName)" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] Using build-tools: $($newestBuildTools.Name) ($((Get-Item $aaptExe).Name))" -ForegroundColor Green

# 4. Read versionName from app/build.gradle.kts
$versionName = "dev"
if (Test-Path $BuildGradleFile) {
    $gradleContent = Get-Content $BuildGradleFile -Raw
    if ($gradleContent -match 'versionName\s*=\s*["'']([^"'']+)["'']') {
        $versionName = $matches[1]
    }
}
Write-Host "[OK] Version Name: $versionName" -ForegroundColor Green

# 5. Git info (commit hash + dirty status)
$gitShortHash = "nogit"
$isDirty = $false
try {
    $commitOut = git -C $RepoRoot rev-parse --short HEAD 2>$null
    if ($LASTEXITCODE -eq 0 -and $commitOut) {
        $gitShortHash = $commitOut.Trim()
    }
} catch {
    $gitShortHash = "nogit"
}
try {
    $statusOut = git -C $RepoRoot status --porcelain 2>$null
    if ($statusOut) {
        $isDirty = $true
    }
} catch {}
$gitTag = if ($isDirty) { "$gitShortHash-dirty" } else { $gitShortHash }
Write-Host "[OK] Git Revision: $gitTag" -ForegroundColor Green

# Ensure stale CMake build files do not reference non-existent SDK paths
$cxxDir = Join-Path $AndroidDir "app\.cxx"
if (Test-Path $cxxDir) {
    Remove-Item -Recurse -Force $cxxDir -ErrorAction SilentlyContinue
}

# 6. Run Gradle build
$taskName = if ($Type -eq "release") { "assembleRelease" } else { "assembleDebug" }
Write-Host "`nRunning: .\gradlew.bat clean $taskName ..." -ForegroundColor Cyan

Push-Location $AndroidDir
try {
    & .\gradlew.bat clean $taskName
    if ($LASTEXITCODE -ne 0) {
        Write-Host "`n[ERROR] Gradle build failed with exit code $LASTEXITCODE." -ForegroundColor Red
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

# 7. Find produced APK
$apkOutputDir = Join-Path $AndroidDir "app\build\outputs\apk\$Type"
if (-not (Test-Path $apkOutputDir)) {
    Write-Host "`n[ERROR] Output directory does not exist: $apkOutputDir" -ForegroundColor Red
    exit 1
}

$sourceApk = Get-ChildItem -Path $apkOutputDir -Filter "*.apk" | Select-Object -First 1
if (-not $sourceApk) {
    Write-Host "`n[ERROR] No APK file generated in: $apkOutputDir" -ForegroundColor Red
    exit 1
}

# 8. Copy to dist
if (-not (Test-Path $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir -Force | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmm"
$destApkName = "saarthi-$versionName-$Type-$timestamp-$gitTag.apk"
$destApkPath = Join-Path $DistDir $destApkName

Copy-Item -Path $sourceApk.FullName -Destination $destApkPath -Force
Write-Host "`n[SUCCESS] APK created and copied to dist!" -ForegroundColor Green

# 9. Compute metadata
$sizeBytes = (Get-Item $destApkPath).Length
$sizeMb = [math]::Round($sizeBytes / 1MB, 2)
$sha256 = (Get-FileHash -Path $destApkPath -Algorithm SHA256).Hash.ToLower()

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Build Artifact Details" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Final Path : $destApkPath"
Write-Host "File Size  : $sizeMb MB ($sizeBytes bytes)"
Write-Host "SHA-256    : $sha256"
Write-Host "Git Commit : $gitTag"

# 10. Inspect package and permissions with aapt
Write-Host "`n--- Package & Permissions Inspection ($((Get-Item $aaptExe).Name)) ---" -ForegroundColor Cyan
$badgingOutput = & $aaptExe dump badging $destApkPath 2>&1

$dumpedPackage = "unknown"
$dumpedVersion = "unknown"
$declaredPermissions = @()

foreach ($line in $badgingOutput) {
    if ($line -match "^package:\s+name='([^']+)'\s+versionCode='([^']*)'\s+versionName='([^']*)'") {
        $dumpedPackage = $matches[1]
        $dumpedVersion = $matches[3]
    }
    if ($line -match "^uses-permission:\s+name='([^']+)'") {
        $declaredPermissions += $matches[1]
    }
}

Write-Host "Package Name : $dumpedPackage"
Write-Host "Version Name : $dumpedVersion"
Write-Host "Declared Permissions ($($declaredPermissions.Count)):"
if ($declaredPermissions.Count -gt 0) {
    foreach ($p in $declaredPermissions) {
        Write-Host "  - $p"
    }
} else {
    Write-Host "  (None declared)"
}

# Offline internet permission check
$hasInternet = $declaredPermissions -contains "android.permission.INTERNET"
Write-Host ""
if ($hasInternet) {
    Write-Host "**********************************************************" -ForegroundColor Red
    Write-Host " LOUD WARNING: INTERNET permission is declared!" -ForegroundColor Red
    Write-Host " The app may NOT be completely offline!" -ForegroundColor Red
    Write-Host "**********************************************************" -ForegroundColor Red
} else {
    Write-Host "OFFLINE OK: no INTERNET permission" -ForegroundColor Green
}

# 11. Append to build_log.txt
$logFile = Join-Path $DistDir "build_log.txt"
$nowLogStr = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$logEntry = "$nowLogStr | $Type | $destApkName | $sha256 | $gitTag"
Add-Content -Path $logFile -Value $logEntry -Encoding utf8
Write-Host "`nLogged build to: $logFile" -ForegroundColor Cyan
