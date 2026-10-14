# C++ Virtual Camera kit (Windows 11 / Media Foundation)

This kit is an integration layer for the public `smourier/VCamSample` Media Foundation virtual-camera sample. It is **not a standalone, verified binary**: the Media Foundation source/COM implementation is taken from that upstream project, and the script applies a small source patch so it reads BGRA frames from shared memory.

## Architecture

- Your C++ application decodes H.264 or MJPEG to uncompressed BGRA (or uses an existing decoded frame).
- Create a `FrameBridge::Writer`, call `Open()` once, then `Write(bgra, width, height)` for each frame from that same process.
- The Media Foundation source DLL is loaded by Windows camera infrastructure in other processes, so the camera source cannot safely consume ordinary in-process pointers. It reads the latest frame through a named shared-memory mapping.
- The camera advertises NV12 (preferred) and RGB32 at 30 fps. Its size is the size announced in the bridge by the client at start (1920x1080 by default, or `--size`), read when the camera is created. The bridge carries BGRA, the camera converts to NV12 per frame.

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

The script clones `https://github.com/smourier/VCamSample` into `upstream`, copies the bridge header, and applies `patches\vcam-bridge.patch`. It stops if the patch no longer applies to the upstream version.

Then the script restores NuGet packages and builds `upstream\VCamSample.sln` as x64 Release with the `v143` toolset (Visual Studio 2022 or Build Tools). Use `.\install.ps1 -Toolset v145` for Visual Studio 2026, or `-SkipBuild` to build manually in Visual Studio.

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

Run `wincam_client.exe` from an elevated terminal (section 5a). It creates and starts the virtual camera "WinCam" itself with `MFCreateVirtualCamera` and removes it on exit. `VCamSample.exe` is not needed and must not run at the same time. Use `--no-vcam` to only fill the bridge.

Test with the Windows Camera app, Discord or OBS.

## 4. Send frames from your own application

Add `bridge\FrameBridge.h` to your C++ project and call:

```cpp
#include "FrameBridge.h"

FrameBridge::Writer writer;
writer.Open();
writer.Write(bgra, width, height);
```

`bgra` points to width*height*4 bytes in BGRA channel order, up to 3840x2160. The writer keeps the mapping open, so `Open()` is called once. `Open()` needs an elevated process because the mapping lives in the `Global\` namespace. The camera source reads the latest frame whenever Windows requests a sample.

The camera size is fixed when the camera is created at client start: the client announces its output size in the bridge (1920x1080 by default, or `--size`) before creating the camera, and always letterboxes the decoded stream into that size, so phone resolution or rotation changes never change the camera size. If the published size differs from the camera size, the camera shows black.
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

This is a test sender. Run it from an elevated terminal. The source must be at most 3840x2160. For a live phone stream use `wincam_client` below.

## 5a. WinCam PC client

`wincam_client.exe` receives the phone stream (Protocol v2) over TCP or USB (AOA), decodes H.264/H.265 with FFmpeg, applies rotation, publishes BGRA frames to the bridge, and creates the virtual camera "WinCam" in the same process (no separate `VCamSample.exe`).

Build (once, FFmpeg through vcpkg takes 15 to 30 minutes the first time):

```bat
git clone https://github.com/microsoft/vcpkg C:\vcpkg
C:\vcpkg\bootstrap-vcpkg.bat
setx VCPKG_ROOT C:\vcpkg
```

Open a new terminal in this directory:

```bat
cmake --preset default
cmake --build --preset release
```

Run from an elevated terminal:

```bat
build\Release\wincam_client.exe --tcp 127.0.0.1:11203
build\Release\wincam_client.exe --tcp 192.168.1.50:11203
build\Release\wincam_client.exe --aoa
```

- ADB mode: run `adb forward tcp:11203 tcp:11203` first.
- AOA mode: needs WinUSB (Zadig) as described in `Server\docs\AOA_PROTOCOL.md`. `--vid` and `--pid` (hex) limit which device is probed.
- `--size WxH` sets the camera output size (default 1920x1080). The stream is rotated, scaled and letterboxed into it.
- `--decoder auto|cpu|gpu` selects decoding. `auto` (default) uses D3D11VA (AMD, NVIDIA and Intel) and falls back to the CPU when the GPU cannot decode the stream. `--gpu INDEX` picks the adapter.
- `--offset DEGREES` is added to the rotation from the stream header (default 270). Key `r` adds another 90 at runtime.
- The client reconnects automatically when the stream or cable drops.

## 6. Unregister / remove the virtual camera

1. Close apps that are using the camera.
2. Exit `wincam_client.exe`; it calls `IMFVirtualCamera::Remove()`.
3. In an elevated Command Prompt, unregister the COM DLL:

```bat
regsvr32 /u VCamSampleSource.dll
```

If the camera remains listed, reboot or restart the Windows camera/frame-server services after closing all camera clients.

## Limitations

- Windows 10 does not expose the same `MFCreateVirtualCamera` API; this kit targets Windows 11.
- The shared-memory transport carries decoded BGRA frames, not H.264/MJPEG packets.
- Camera resolution is fixed when an application opens the camera (see section 4); 30 fps is fixed.
- The patch modifies an upstream sample. Review its license and source before redistribution.
- This integration has not been compiled or run in this environment. Verify it on your target Windows/SDK version; it is provided as source and integration instructions, not as a prebuilt guaranteed-working driver.
