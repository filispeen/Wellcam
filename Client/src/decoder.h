#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>

#include "protocol.h"

struct AVCodecContext;
struct AVPacket;
struct AVFrame;
struct AVBufferRef;

namespace wellcam
{
    enum class DecoderMode
    {
        Auto,
        Cpu,
        Gpu
    };

    class Decoder
    {
    public:
        using FrameHandler = std::function<void(const AVFrame&)>;

        Decoder() = default;
        Decoder(const Decoder&) = delete;
        Decoder& operator=(const Decoder&) = delete;
        ~Decoder() { Close(); }

        bool Open(Codec codec, DecoderMode mode, int gpuIndex);
        void Close();
        bool IsOpen() const { return context_ != nullptr; }
        bool LastFrameHardware() const { return lastHardware_; }
        void Decode(const uint8_t* data, size_t size, int64_t pts, const FrameHandler& onFrame);

    private:
        bool OpenContext(Codec codec, bool hardware, int gpuIndex);

        AVCodecContext* context_ = nullptr;
        AVPacket* packet_ = nullptr;
        AVFrame* frame_ = nullptr;
        AVFrame* softwareFrame_ = nullptr;
        AVBufferRef* hardwareDevice_ = nullptr;
        bool lastHardware_ = false;
    };
}
