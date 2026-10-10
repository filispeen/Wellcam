$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Join-Path $root "upstream"
$sourceDir = Join-Path $repo "VCamSampleSource"
$streamCpp = Join-Path $sourceDir "MediaStream.cpp"

if (!(Test-Path $repo)) {
    git clone --depth 1 https://github.com/smourier/VCamSample.git $repo
}
if (!(Test-Path $streamCpp)) {
    throw "Could not find upstream VCamSampleSource\MediaStream.cpp. Check the repository layout."
}

Copy-Item (Join-Path $root "bridge\FrameBridge.h") (Join-Path $sourceDir "FrameBridge.h") -Force

$src = Get-Content $streamCpp -Raw
if ($src -notmatch '#include "FrameBridge.h"') {
    $src = $src.Replace('#include "MediaSource.h"', "#include `"MediaSource.h`"`r`n#include `"FrameBridge.h`"")
}

# Force a single advertised type (RGB32). The bridge supplies decoded BGRA/RGB32 frames.
if ($src -match 'make_unique_cotaskmem_array<IMFMediaType>>\(2\)') {
    $src = [regex]::Replace($src, 'make_unique_cotaskmem_array<IMFMediaType>>\(2\)', 'make_unique_cotaskmem_array<IMFMediaType>>(1)', 1)
} elseif ($src -notmatch 'make_unique_cotaskmem_array<IMFMediaType>>\(1\)') {
    throw "Could not find media type array declaration; refusing to patch an unknown source version."
}

$pattern = '(?s)// generate frame\s+wil::com_ptr_nothrow<IMFSample>\s+outSample;\s+RETURN_IF_FAILED\(_generator\.Generate\(sample\.get\(\),\s*_format,\s*&outSample\)\);'
$replacement = @'
// Copy the latest BGRA frame from the shared-memory bridge.
    wil::com_ptr_nothrow<IMFMediaBuffer> frameBuffer;
    RETURN_IF_FAILED(sample->ConvertToContiguousBuffer(&frameBuffer));
    BYTE* destination = nullptr;
    DWORD maxLength = 0;
    DWORD currentLength = 0;
    RETURN_IF_FAILED(frameBuffer->Lock(&destination, &maxLength, &currentLength));
    constexpr DWORD frameBytes = FrameBridge::Width * FrameBridge::Height * 4;
    if (maxLength < frameBytes)
    {
        frameBuffer->Unlock();
        RETURN_HR(MF_E_BUFFERTOOSMALL);
    }
    if (!FrameBridge::ReadBGRA(destination, frameBytes))
    {
        ZeroMemory(destination, frameBytes);
    }
    RETURN_IF_FAILED(frameBuffer->SetCurrentLength(frameBytes));
    RETURN_IF_FAILED(frameBuffer->Unlock());

    wil::com_ptr_nothrow<IMFSample> outSample = sample;
'@
if ($src -match $pattern) {
    $src = [regex]::Replace($src, $pattern, [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $replacement }, 1)
} elseif ($src -notmatch 'FrameBridge::ReadBGRA') {
    throw "Could not find frame-generation block; refusing to patch an unknown source version."
}

# The bridge writes CPU memory, so disable the sample's optional D3D allocator path.
$d3dReplacement = @"
HRESULT MediaStream::SetD3DManager(IUnknown* manager)
{
    UNREFERENCED_PARAMETER(manager);
    return S_OK;
}
"@
$src = [regex]::Replace(
    $src,
    '(?s)HRESULT MediaStream::SetD3DManager\(IUnknown\* manager\)\s*\{.*?\n\}',
    [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $d3dReplacement },
    1
)

Set-Content -Path $streamCpp -Value $src -Encoding UTF8
Write-Host "Patched $streamCpp"
Write-Host "Next: open upstream\VCamSample.sln, build x64 Release, then follow README.md."
