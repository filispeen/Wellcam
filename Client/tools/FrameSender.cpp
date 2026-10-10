#define NOMINMAX
#include <windows.h>
#include <mfapi.h>
#include <mfreadwrite.h>
#include <mferror.h>
#include <iostream>
#include <vector>
#include <string>
#include <thread>
#include <chrono>
#include "../bridge/FrameBridge.h"

#pragma comment(lib, "mfplat.lib")
#pragma comment(lib, "mfreadwrite.lib")
#pragma comment(lib, "mfuuid.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "advapi32.lib")

static HRESULT SetRGB32(IMFSourceReader* reader)
{
    IMFMediaType* type = nullptr;
    HRESULT hr = MFCreateMediaType(&type);
    if (SUCCEEDED(hr)) hr = type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    if (SUCCEEDED(hr)) hr = type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    if (SUCCEEDED(hr)) hr = reader->SetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type);
    if (type) type->Release();
    return hr;
}

int wmain(int argc, wchar_t** argv)
{
    if (argc < 2) {
        std::wcerr << L"Usage: FrameSender.exe <video-file-or-MF-supported-URL>\n";
        return 2;
    }

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) return 1;
    hr = MFStartup(MF_VERSION);
    if (FAILED(hr)) { CoUninitialize(); return 1; }

    IMFAttributes* attrs = nullptr;
    IMFSourceReader* reader = nullptr;
    hr = MFCreateAttributes(&attrs, 1);
    if (SUCCEEDED(hr)) hr = attrs->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, TRUE);
    if (SUCCEEDED(hr)) hr = MFCreateSourceReaderFromURL(argv[1], attrs, &reader);
    if (attrs) attrs->Release();

    if (SUCCEEDED(hr)) hr = SetRGB32(reader);

    UINT32 sourceWidth = 0, sourceHeight = 0;
    IMFMediaType* currentType = nullptr;
    if (SUCCEEDED(hr)) hr = reader->GetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, &currentType);
    if (SUCCEEDED(hr)) hr = MFGetAttributeSize(
        currentType, MF_MT_FRAME_SIZE, &sourceWidth, &sourceHeight);
    if (currentType) currentType->Release();

    if (SUCCEEDED(hr) &&
        (sourceWidth != FrameBridge::Width || sourceHeight != FrameBridge::Height)) {
        std::wcerr << L"Demo sender requires 1280x960 input after RGB32 conversion; "
                      L"resize your source in your app before publishing.\n";
        hr = MF_E_INVALIDMEDIATYPE;
    }

    if (FAILED(hr)) {
        std::wcerr << L"Could not open/decode source, HRESULT=0x"
                   << std::hex << static_cast<unsigned long>(hr) << L"\n";
        if (reader) reader->Release();
        MFShutdown(); CoUninitialize(); return 1;
    }

    std::vector<uint8_t> scaled(FrameBridge::FrameBytes);
    std::wcout << L"Publishing decoded frames. Ctrl+C to stop.\n";
    for (;;) {
        DWORD streamIndex = 0, flags = 0;
        LONGLONG timestamp = 0;
        IMFSample* sample = nullptr;
        hr = reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                                &streamIndex, &flags, &timestamp, &sample);
        if (FAILED(hr) || (flags & MF_SOURCE_READERF_ERROR)) {
            if (sample) sample->Release();
            break;
        }
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
            if (sample) sample->Release();
            // Stop at end-of-stream; restart the source explicitly if looping is needed.
            break;
        }

        if (sample) {
            IMFMediaBuffer* buffer = nullptr;
            hr = sample->ConvertToContiguousBuffer(&buffer);
            if (SUCCEEDED(hr)) {
                BYTE* src = nullptr;
                DWORD maxLen = 0, curLen = 0;
                hr = buffer->Lock(&src, &maxLen, &curLen);
                if (SUCCEEDED(hr)) {
                    // Source Reader RGB32 output may not match the camera's fixed
                    // dimensions. This demo only accepts exactly 1280x960.
                    if (curLen >= FrameBridge::FrameBytes) {
                        FrameBridge::WriteBGRA(src, FrameBridge::Width, FrameBridge::Height);
                    }
                    buffer->Unlock();
                }
                buffer->Release();
            }
            sample->Release();
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(33));
    }

    reader->Release();
    MFShutdown();
    CoUninitialize();
    return 0;
}
