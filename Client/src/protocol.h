#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace wincam
{
    enum class Codec : uint8_t
    {
        H264 = 1,
        H265 = 2
    };

    enum class PacketType : uint8_t
    {
        Config = 0,
        Frame = 1
    };

    struct StreamHeader
    {
        Codec codec;
        uint16_t width;
        uint16_t height;
        uint16_t fps;
        uint16_t rotation;
    };

    struct Packet
    {
        PacketType type;
        bool keyframe;
        uint64_t pts;
        const uint8_t* data;
        size_t size;
    };

    class StreamParser
    {
    public:
        using HeaderHandler = std::function<void(const StreamHeader&)>;
        using PacketHandler = std::function<void(const Packet&)>;

        StreamParser(HeaderHandler onHeader, PacketHandler onPacket);

        void Feed(const uint8_t* data, size_t size);
        void Reset();
        size_t SkippedBytes() const { return skipped_; }

    private:
        enum class State
        {
            Sync,
            Packets
        };

        bool Synchronize();
        void Compact();

        HeaderHandler onHeader_;
        PacketHandler onPacket_;
        std::vector<uint8_t> buffer_;
        size_t pos_ = 0;
        size_t skipped_ = 0;
        State state_ = State::Sync;
    };
}
