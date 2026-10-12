#include "vcam.h"

#include <windows.h>
#include <combaseapi.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfvirtualcamera.h>

namespace wincam
{
    namespace
    {
        const GUID kCameraClsid = {0x3cad447d, 0xf283, 0x4af4, {0xa3, 0xb2, 0x6f, 0x53, 0x63, 0x30, 0x9f, 0x52}};
    }

    struct VirtualCamera::State
    {
        bool comReady = false;
        bool mediaFoundationReady = false;
        IMFVirtualCamera* camera = nullptr;
    };

    VirtualCamera::~VirtualCamera()
    {
        Stop();
    }

    void VirtualCamera::Destroy(State* state)
    {
        if (!state) return;
        if (state->camera) state->camera->Release();
        if (state->mediaFoundationReady) MFShutdown();
        if (state->comReady) CoUninitialize();
        delete state;
    }

    long VirtualCamera::Start(const std::wstring& name)
    {
        if (state_) return S_OK;

        State* state = new State();

        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (SUCCEEDED(hr))
        {
            state->comReady = true;
        }
        else if (hr != RPC_E_CHANGED_MODE)
        {
            Destroy(state);
            return hr;
        }

        hr = MFStartup(MF_VERSION);
        if (FAILED(hr))
        {
            Destroy(state);
            return hr;
        }
        state->mediaFoundationReady = true;

        wchar_t clsid[64] = {};
        StringFromGUID2(kCameraClsid, clsid, static_cast<int>(sizeof(clsid) / sizeof(clsid[0])));

        hr = MFCreateVirtualCamera(
            MFVirtualCameraType_SoftwareCameraSource,
            MFVirtualCameraLifetime_Session,
            MFVirtualCameraAccess_CurrentUser,
            name.c_str(),
            clsid,
            nullptr,
            0,
            &state->camera);
        if (SUCCEEDED(hr)) hr = state->camera->Start(nullptr);
        if (FAILED(hr))
        {
            Destroy(state);
            return hr;
        }

        state_ = state;
        return S_OK;
    }

    void VirtualCamera::Stop()
    {
        if (!state_) return;
        if (state_->camera) state_->camera->Remove();
        Destroy(state_);
        state_ = nullptr;
    }
}
