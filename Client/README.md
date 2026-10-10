# C++ Virtual Camera kit (Windows 11 / Media Foundation)

This kit is an integration layer for the public `smourier/VCamSample` Media Foundation virtual-camera sample. It is **not a standalone, verified binary**: the Media Foundation source/COM implementation is taken from that upstream project, and the script applies a small source patch so it reads BGRA frames from shared memory.

## Architecture

- Your C++ application decodes H.264 or MJPEG to uncompressed BGRA (or uses an existing decoded frame).
- Call `FrameBridge::WriteBGRA(...)` from that same process.
- The Media Foundation source DLL is loaded by Windows camera infrastructure in other processes, so the camera source cannot safely consume ordinary in-process pointers. It reads the latest frame through a named shared-memory mapping.
- The camera advertises RGB32 at 1280x960 / 30 fps in this starter integration. The Windows capture stack or client may convert it if needed.

**Important:** H.264/MJPEG are encoded formats. A virtual camera generally publishes decoded video frames, not the incoming compressed bitstream directly. This kit's demo sender uses Media Foundation Source Reader to decode a video file or URL to BGRA. For a live H.264/MJPEG stream already handled by your app, integrate the `WriteBGRA` call after your existing decoder.

## Requirements

- Windows 11 (build 22000 or newer)
- Visual Studio 2022 with **Desktop development with C++**
- Windows SDK 10.0.22000.0 or newer
- Git installed
- Administrator terminal for COM registration

## 1. Prepare upstream sample

Open PowerShell in this directory and run:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\install.ps1
```

The script clones `https://github.com/smourier/VCamSample` into `upstream`, copies the bridge header, and patches `MediaStream.cpp` to copy frames from shared memory. It stops if the expected source lines have changed.

Then open `upstream\VCamSample.sln` in Visual Studio, select **x64** and **Release**, and build the solution.

The upstream project has its own dependencies and build configuration; use its README if Visual Studio asks to restore dependencies.

## 2. Register the Media Source COM DLL

Copy the built output to a directory readable by Windows camera services, for example:

`C:\ProgramData\CppVirtualCamera\`

Do not keep it under a private user profile directory. Copy all required DLLs from the same output folder.

Run an elevated Command Prompt, change to the directory containing `VCamSampleSource.dll`, then:

```bat
regsvr32 VCamSampleSource.dll
```

Use the matching 64-bit build and 64-bit `regsvr32` from `C:\Windows\System32`.

## 3. Start the virtual camera

Run the built `VCamSample.exe`. The upstream app creates and starts the virtual camera and removes it when the app exits. Keep it running while testing.

Test with the Windows Camera app or OBS. The camera name is controlled by the upstream sample.

## 4. Send frames from your own application

Add `bridge\FrameBridge.h` to your C++ project and call:

```cpp
#include "FrameBridge.h"

// `bgra` points to width*height*4 bytes in BGRA channel order.
FrameBridge::WriteBGRA(bgra, width, height);
```

The current bridge expects **1280x960 BGRA32**. Resize/convert before calling it. `WriteBGRA` copies the frame into the shared mapping. The camera source reads the latest frame whenever Windows requests a sample.

Example:
```cpp
// after decoding a H.264/MJPEG frame to BGRA:
if (width == 1280 && height == 960) {
    FrameBridge::WriteBGRA(decodedBgra.data(), width, height);
}
```

The mapping uses a global named object and an ACL that allows LocalSystem, Administrators, and authenticated users. This is intended for a local development machine; do not use it as-is in a multi-user/security-sensitive deployment. Change the names and security descriptor for your environment.

## 5. Demo sender (optional)

`tools\FrameSender.cpp` reads a local video file or Media Foundation-supported URL, asks Source Reader to decode to RGB32, and publishes the frames to the bridge. Build from an x64 Native Tools Command Prompt:

```bat
cl /std:c++17 /EHsc tools\FrameSender.cpp /link mfplat.lib mfreadwrite.lib mfuuid.lib ole32.lib
```

Run:
```bat
FrameSender.exe "C:\path\to\video.mp4"
```

This is a test sender. For a live compressed stream, use the decoder already present in your application and call `WriteBGRA` with its decoded frames.

## 6. Unregister / remove the virtual camera

1. Close apps that are using the camera.
2. Exit `VCamSample.exe`; it calls `IMFVirtualCamera::Remove()`.
3. In an elevated Command Prompt, unregister the COM DLL:

```bat
regsvr32 /u VCamSampleSource.dll
```

If the camera remains listed, reboot or restart the Windows camera/frame-server services after closing all camera clients.

## Limitations

- Windows 10 does not expose the same `MFCreateVirtualCamera` API; this kit targets Windows 11.
- The shared-memory transport carries decoded BGRA frames, not H.264/MJPEG packets.
- Resolution and frame rate are fixed in this patch (1280x960, 30 fps).
- The patch modifies an upstream sample. Review its license and source before redistribution.
- This integration has not been compiled or run in this environment. Verify it on your target Windows/SDK version; it is provided as source and integration instructions, not as a prebuilt guaranteed-working driver.
