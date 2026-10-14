#include "protocol.h"

#include <algorithm>
#include <utility>

namespace wincam
{
    namespace
    {
        constexpr size_t HeaderSize = 14;
        constexpr size_t PacketHeaderSize = 14;
        constexpr size_t MagicPrefix = 6;
        constexpr uint32_t MaxPayload = 16u * 1024u * 1024u;

        uint16_t Be16(const uint8_t* p)
        {
            return static_cast<uint16_t>((p[0] << 8) | p[1]);
        }

        uint32_t Be32(const uint8_t* p)
        {
            return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
                   (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
        }

        uint64_t Be64(const uint8_t* p)
        {
            return (static_cast<uint64_t>(Be32(p)) << 32) | Be32(p + 4);
        }

        bool IsHeaderStart(const uint8_t* p)
        {
            return p[0] == 'W' && p[1] == 'C' && p[2] == 'A' && p[3] == 'M' && p[4] == 2 &&
                   (p[5] == 1 || p[5] == 2);
        }
    }

    StreamParser::StreamParser(HeaderHandler onHeader, PacketHandler onPacket)
        : onHeader_(std::move(onHeader)), onPacket_(std::move(onPacket))
    {
    }

    void StreamParser::Reset()
    {
        buffer_.clear();
        pos_ = 0;
        state_ = State::Sync;
    }

    bool StreamParser::Synchronize()
    {
        const size_t available = buffer_.size() - pos_;
        if (available >= MagicPrefix)
        {
            const uint8_t* base = buffer_.data() + pos_;
            for (size_t i = 0; i + MagicPrefix <= available; ++i)
            {
                if (IsHeaderStart(base + i))
                {
                    skipped_ += i;
                    pos_ += i;
                    return true;
                }
            }
        }

        const size_t keep = std::min<size_t>(available, MagicPrefix - 1);
        const size_t drop = available - keep;
        skipped_ += drop;
        pos_ += drop;
        return false;
    }

    void StreamParser::Compact()
    {
        if (pos_ == 0) return;
        if (pos_ == buffer_.size())
        {
            buffer_.clear();
            pos_ = 0;
        }
        else if (pos_ >= 1u << 20)
        {
            buffer_.erase(buffer_.begin(), buffer_.begin() + static_cast<std::ptrdiff_t>(pos_));
            pos_ = 0;
        }
    }

    void StreamParser::Feed(const uint8_t* data, size_t size)
    {
        buffer_.insert(buffer_.end(), data, data + size);

        for (;;)
        {
            if (state_ == State::Sync)
            {
                if (!Synchronize()) break;
                if (buffer_.size() - pos_ < HeaderSize) break;

                const uint8_t* p = buffer_.data() + pos_;
                StreamHeader header{};
                header.codec = static_cast<Codec>(p[5]);
                header.width = Be16(p + 6);
                header.height = Be16(p + 8);
                header.fps = Be16(p + 10);
                header.rotation = Be16(p + 12);
                pos_ += HeaderSize;
                state_ = State::Packets;
                onHeader_(header);
                continue;
            }

            if (buffer_.size() - pos_ < PacketHeaderSize) break;

            const uint8_t* p = buffer_.data() + pos_;
            const uint8_t type = p[0];
            const uint32_t payload = Be32(p + 10);
            if (type > 1 || payload > MaxPayload)
            {
                state_ = State::Sync;
                pos_ += 1;
                continue;
            }

            if (buffer_.size() - pos_ < PacketHeaderSize + payload) break;

            Packet packet{};
            packet.type = static_cast<PacketType>(type);
            packet.keyframe = (p[1] & 1) != 0;
            packet.orientation = static_cast<uint8_t>((p[1] >> 1) & 3);
            packet.pts = Be64(p + 2);
            packet.data = p + PacketHeaderSize;
            packet.size = payload;
            onPacket_(packet);
            pos_ += PacketHeaderSize + payload;
        }

        Compact();
    }
}
