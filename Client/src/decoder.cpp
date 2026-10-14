#include "decoder.h"

#include <cstdio>
#include <cstring>

extern "C"
{
#include <libavcodec/avcodec.h>
#include <libavutil/buffer.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/pixfmt.h>
}

namespace wellcam
{
    namespace
    {
        AVPixelFormat PickFormat(AVCodecContext* context, const AVPixelFormat* formats)
        {
            for (const AVPixelFormat* p = formats; *p != AV_PIX_FMT_NONE; ++p)
            {
                if (*p == AV_PIX_FMT_D3D11) return *p;
            }
            return avcodec_default_get_format(context, formats);
        }
    }

    bool Decoder::Open(Codec codec, DecoderMode mode, int gpuIndex)
    {
        Close();

        if (mode != DecoderMode::Cpu && OpenContext(codec, true, gpuIndex)) return true;
        if (mode == DecoderMode::Gpu) return false;
        return OpenContext(codec, false, gpuIndex);
    }

    bool Decoder::OpenContext(Codec codec, bool hardware, int gpuIndex)
    {
        Close();

        const AVCodecID id = codec == Codec::H265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
        const AVCodec* decoder = avcodec_find_decoder(id);
        if (!decoder) return false;

        context_ = avcodec_alloc_context3(decoder);
        packet_ = av_packet_alloc();
        frame_ = av_frame_alloc();
        softwareFrame_ = av_frame_alloc();
        if (!context_ || !packet_ || !frame_ || !softwareFrame_)
        {
            Close();
            return false;
        }

        context_->flags |= AV_CODEC_FLAG_LOW_DELAY;

        if (hardware)
        {
            char device[16];
            std::snprintf(device, sizeof(device), "%d", gpuIndex);
            if (av_hwdevice_ctx_create(&hardwareDevice_, AV_HWDEVICE_TYPE_D3D11VA, device, nullptr, 0) < 0)
            {
                Close();
                return false;
            }
            context_->hw_device_ctx = av_buffer_ref(hardwareDevice_);
            context_->get_format = PickFormat;
            context_->thread_count = 1;
        }
        else
        {
            context_->thread_count = 0;
        }

        if (avcodec_open2(context_, decoder, nullptr) < 0)
        {
            Close();
            return false;
        }
        return true;
    }

    void Decoder::Close()
    {
        if (softwareFrame_) av_frame_free(&softwareFrame_);
        if (frame_) av_frame_free(&frame_);
        if (packet_) av_packet_free(&packet_);
        if (context_) avcodec_free_context(&context_);
        if (hardwareDevice_) av_buffer_unref(&hardwareDevice_);
        lastHardware_ = false;
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
            if (frame_->format == AV_PIX_FMT_D3D11)
            {
                av_frame_unref(softwareFrame_);
                if (av_hwframe_transfer_data(softwareFrame_, frame_, 0) >= 0)
                {
                    av_frame_copy_props(softwareFrame_, frame_);
                    lastHardware_ = true;
                    onFrame(*softwareFrame_);
                }
            }
            else
            {
                lastHardware_ = false;
                onFrame(*frame_);
            }
            av_frame_unref(frame_);
        }
    }
}
