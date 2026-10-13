#pragma once

#include <cstdint>
#include <vector>

#include "FrameBridge.h"

struct AVFrame;
struct SwsContext;

namespace wincam
{
    class Renderer
    {
    public:
        Renderer(FrameBridge::Writer& writer, uint32_t forcedWidth, uint32_t forcedHeight);
        Renderer(const Renderer&) = delete;
        Renderer& operator=(const Renderer&) = delete;
        ~Renderer();

        void SetRotation(int degrees);
        bool Present(const AVFrame& frame);
        uint32_t OutputWidth() const { return lockedWidth_; }
        uint32_t OutputHeight() const { return lockedHeight_; }

    private:
        void LockOutput(uint32_t width, uint32_t height);

        FrameBridge::Writer& writer_;
        int rotation_ = 0;
        uint32_t lockedWidth_ = 0;
        uint32_t lockedHeight_ = 0;
        uint64_t layoutKey_ = 0;
        SwsContext* convert_ = nullptr;
        std::vector<uint8_t> converted_;
        std::vector<uint8_t> canvas_;
    };
}
