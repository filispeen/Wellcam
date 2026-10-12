param(
    [string]$Toolset = "v143",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Join-Path $root "upstream"
$sourceDir = Join-Path $repo "VCamSampleSource"
$patch = Join-Path $root "patches\vcam-bridge.patch"

if (!(Test-Path $repo)) {
    git clone --depth 1 https://github.com/smourier/VCamSample.git $repo
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}
if (!(Test-Path (Join-Path $sourceDir "MediaStream.cpp"))) {
    throw "Could not find upstream VCamSampleSource\MediaStream.cpp. Check the repository layout."
}

Copy-Item (Join-Path $root "bridge\FrameBridge.h") (Join-Path $sourceDir "FrameBridge.h") -Force

Push-Location $repo
try {
    git apply --reverse --check $patch 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Patch already applied"
    } else {
        git apply --check $patch
        if ($LASTEXITCODE -ne 0) { throw "Patch does not apply to this upstream version" }
        git apply $patch
        if ($LASTEXITCODE -ne 0) { throw "git apply failed" }
        Write-Host "Patched VCamSampleSource"
    }
} finally {
    Pop-Location
}

if ($SkipBuild) {
    Write-Host "Skipped build. Open upstream\VCamSample.sln, build x64 Release, then follow README.md."
    return
}

$tools = Join-Path $root "build\tools"
New-Item -ItemType Directory -Force $tools | Out-Null
$nuget = Join-Path $tools "nuget.exe"
if (!(Test-Path $nuget)) {
    Invoke-WebRequest "https://dist.nuget.org/win-x86-commandline/latest/nuget.exe" -OutFile $nuget -UseBasicParsing
}

$solution = Join-Path $repo "VCamSample.sln"
& $nuget restore $solution -Source "https://api.nuget.org/v3/index.json"
if ($LASTEXITCODE -ne 0) { throw "nuget restore failed" }

$installer = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $installer -latest -products * -requires Microsoft.Component.MSBuild -property installationPath
if (!$vs) { throw "Visual Studio or Build Tools with MSBuild was not found" }
$msbuild = Join-Path $vs "MSBuild\Current\Bin\MSBuild.exe"

& $msbuild $solution /p:Configuration=Release /p:Platform=x64 /p:PlatformToolset=$Toolset /m /v:minimal
if ($LASTEXITCODE -ne 0) { throw "MSBuild failed" }

Write-Host "Built upstream\x64\Release. Next: regsvr32 VCamSampleSource.dll from an elevated terminal, then run VCamSample.exe."
