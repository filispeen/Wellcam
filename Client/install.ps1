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
        Write-Host "Patched MediaStream.cpp and MediaStream.h"
    }
} finally {
    Pop-Location
}

Write-Host "Next: open upstream\VCamSample.sln, build x64 Release, then follow README.md."
