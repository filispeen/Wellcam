#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

#include "protocol.h"

struct AVCodecContext;
struct AVPacket;
struct AVFrame;

namespace wincam
{
    class Decoder
    {
    public:
        using FrameHandler = std::function<void(const AVFrame&)>;

        Decoder() = default;
        Decoder(const Decoder&) = delete;
        Decoder& operator=(const Decoder&) = delete;
        ~Decoder() { Close(); }

        bool Open(Codec codec);
        void Close();
        bool IsOpen() const { return context_ != nullptr; }
        void Decode(const uint8_t* data, size_t size, int64_t pts, const FrameHandler& onFrame);

    private:
        AVCodecContext* context_ = nullptr;
        AVPacket* packet_ = nullptr;
        AVFrame* frame_ = nullptr;
    };
}
