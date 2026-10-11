#include "decoder.h"

#include <cstring>

extern "C"
{
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
}

namespace wincam
{
    bool Decoder::Open(Codec codec)
    {
        Close();

        const AVCodecID id = codec == Codec::H265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
        const AVCodec* decoder = avcodec_find_decoder(id);
        if (!decoder) return false;

        context_ = avcodec_alloc_context3(decoder);
        packet_ = av_packet_alloc();
        frame_ = av_frame_alloc();
        if (!context_ || !packet_ || !frame_)
        {
            Close();
            return false;
        }

        context_->flags |= AV_CODEC_FLAG_LOW_DELAY;
        context_->thread_count = 0;

        if (avcodec_open2(context_, decoder, nullptr) < 0)
        {
            Close();
            return false;
        }
        return true;
    }

    void Decoder::Close()
    {
        if (frame_) av_frame_free(&frame_);
        if (packet_) av_packet_free(&packet_);
        if (context_) avcodec_free_context(&context_);
    }

    void Decoder::Decode(const uint8_t* data, size_t size, int64_t pts, const FrameHandler& onFrame)
    {
        if (!context_ || size == 0) return;

        if (av_new_packet(packet_, static_cast<int>(size)) < 0) return;
        std::memcpy(packet_->data, data, size);
        packet_->pts = pts;
        packet_->dts = pts;

        const int sent = avcodec_send_packet(context_, packet_);
        av_packet_unref(packet_);
        if (sent < 0 && sent != AVERROR(EAGAIN)) return;

        while (avcodec_receive_frame(context_, frame_) == 0)
        {
            onFrame(*frame_);
            av_frame_unref(frame_);
        }
    }
}
