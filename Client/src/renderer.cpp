#include "renderer.h"

#include <algorithm>
#include <cstdint>
#include <cstring>

extern "C"
{
#include <libavutil/frame.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

namespace wincam
{
    namespace
    {
        int Coefficients(const AVFrame& frame)
        {
            switch (frame.colorspace)
            {
            case AVCOL_SPC_BT709:
                return SWS_CS_ITU709;
            case AVCOL_SPC_BT470BG:
            case AVCOL_SPC_SMPTE170M:
                return SWS_CS_ITU601;
            case AVCOL_SPC_BT2020_NCL:
            case AVCOL_SPC_BT2020_CL:
                return SWS_CS_BT2020;
            default:
                return frame.height >= 720 ? SWS_CS_ITU709 : SWS_CS_ITU601;
            }
        }

        void RotateInto(const uint32_t* src, uint32_t width, uint32_t height, int degrees, uint32_t* dst, size_t dstStride)
        {
            if (degrees == 0)
            {
                for (uint32_t y = 0; y < height; ++y)
                    std::memcpy(dst + y * dstStride, src + static_cast<size_t>(y) * width, static_cast<size_t>(width) * 4);
                return;
            }

            if (degrees == 180)
            {
                for (uint32_t y = 0; y < height; ++y)
                {
                    const uint32_t* row = src + static_cast<size_t>(height - 1 - y) * width;
                    uint32_t* out = dst + y * dstStride;
                    for (uint32_t x = 0; x < width; ++x) out[x] = row[width - 1 - x];
                }
                return;
            }

            constexpr uint32_t tile = 32;
            for (uint32_t r0 = 0; r0 < width; r0 += tile)
            {
                const uint32_t r1 = std::min(r0 + tile, width);
                for (uint32_t c0 = 0; c0 < height; c0 += tile)
                {
                    const uint32_t c1 = std::min(c0 + tile, height);
                    if (degrees == 90)
                    {
                        for (uint32_t r = r0; r < r1; ++r)
                            for (uint32_t c = c0; c < c1; ++c)
                                dst[static_cast<size_t>(r) * dstStride + c] = src[static_cast<size_t>(height - 1 - c) * width + r];
                    }
                    else
                    {
                        for (uint32_t r = r0; r < r1; ++r)
                            for (uint32_t c = c0; c < c1; ++c)
                                dst[static_cast<size_t>(r) * dstStride + c] = src[static_cast<size_t>(c) * width + (width - 1 - r)];
                    }
                }
            }
        }
    }

    Renderer::Renderer(FrameBridge::Writer& writer, uint32_t forcedWidth, uint32_t forcedHeight)
        : writer_(writer)
    {
        if (forcedWidth > 0 && forcedHeight > 0)
            LockOutput(forcedWidth, forcedHeight);
        else
            LockOutput(FrameBridge::DefaultWidth, FrameBridge::DefaultHeight);
    }

    Renderer::~Renderer()
    {
        if (convert_) sws_freeContext(convert_);
    }

    void Renderer::SetRotation(int degrees)
    {
        rotation_ = (((degrees % 360) + 360) % 360) / 90 * 90;
    }

    void Renderer::LockOutput(uint32_t width, uint32_t height)
    {
        if (width == 0 || height == 0) return;
        const double fit = std::min({1.0,
                                     static_cast<double>(FrameBridge::MaxWidth) / width,
                                     static_cast<double>(FrameBridge::MaxHeight) / height});
        lockedWidth_ = std::max<uint32_t>(2, static_cast<uint32_t>(width * fit) & ~1u);
        lockedHeight_ = std::max<uint32_t>(2, static_cast<uint32_t>(height * fit) & ~1u);
    }

    bool Renderer::Present(const AVFrame& frame)
    {
        const int srcWidth = frame.width;
        const int srcHeight = frame.height;
        if (srcWidth <= 0 || srcHeight <= 0 || frame.format < 0 || lockedWidth_ == 0) return false;

        const bool quarter = rotation_ == 90 || rotation_ == 270;
        const uint32_t rotatedWidth = static_cast<uint32_t>(quarter ? srcHeight : srcWidth);
        const uint32_t rotatedHeight = static_cast<uint32_t>(quarter ? srcWidth : srcHeight);

        const double factor = std::min(static_cast<double>(lockedWidth_) / rotatedWidth,
                                       static_cast<double>(lockedHeight_) / rotatedHeight);
        const uint32_t fitWidth = std::max<uint32_t>(1, static_cast<uint32_t>(rotatedWidth * factor));
        const uint32_t fitHeight = std::max<uint32_t>(1, static_cast<uint32_t>(rotatedHeight * factor));
        const uint32_t offsetX = (lockedWidth_ - std::min(fitWidth, lockedWidth_)) / 2;
        const uint32_t offsetY = (lockedHeight_ - std::min(fitHeight, lockedHeight_)) / 2;

        const uint32_t preWidth = quarter ? fitHeight : fitWidth;
        const uint32_t preHeight = quarter ? fitWidth : fitHeight;

        convert_ = sws_getCachedContext(
            convert_, srcWidth, srcHeight, static_cast<AVPixelFormat>(frame.format),
            static_cast<int>(preWidth), static_cast<int>(preHeight), AV_PIX_FMT_BGRA, SWS_BILINEAR,
            nullptr, nullptr, nullptr);
        if (!convert_) return false;

        sws_setColorspaceDetails(
            convert_, sws_getCoefficients(Coefficients(frame)),
            frame.color_range == AVCOL_RANGE_JPEG ? 1 : 0,
            sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);

        converted_.resize(static_cast<size_t>(preWidth) * preHeight * 4);
        uint8_t* convertedPlanes[4] = {converted_.data(), nullptr, nullptr, nullptr};
        const int convertedStrides[4] = {static_cast<int>(preWidth) * 4, 0, 0, 0};
        sws_scale(convert_, frame.data, frame.linesize, 0, srcHeight, convertedPlanes, convertedStrides);

        const size_t canvasPixels = static_cast<size_t>(lockedWidth_) * lockedHeight_;
        const uint64_t key = (static_cast<uint64_t>(fitWidth) << 32) | fitHeight;
        if (canvas_.size() != canvasPixels * 4 || layoutKey_ != key)
        {
            canvas_.resize(canvasPixels * 4);
            auto* fill = reinterpret_cast<uint32_t*>(canvas_.data());
            std::fill(fill, fill + canvasPixels, 0xFF000000u);
            layoutKey_ = key;
        }

        auto* canvas32 = reinterpret_cast<uint32_t*>(canvas_.data());
        RotateInto(reinterpret_cast<const uint32_t*>(converted_.data()), preWidth, preHeight, rotation_,
                   canvas32 + static_cast<size_t>(offsetY) * lockedWidth_ + offsetX, lockedWidth_);

        return writer_.Write(canvas_.data(), lockedWidth_, lockedHeight_);
    }
}
