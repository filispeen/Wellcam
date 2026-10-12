#pragma once

#include <string>

namespace wincam
{
    class VirtualCamera
    {
    public:
        VirtualCamera() = default;
        VirtualCamera(const VirtualCamera&) = delete;
        VirtualCamera& operator=(const VirtualCamera&) = delete;
        ~VirtualCamera();

        long Start(const std::wstring& name);
        void Stop();

    private:
        struct State;
        static void Destroy(State* state);

        State* state_ = nullptr;
    };
}
