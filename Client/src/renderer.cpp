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

        void Rotate(const uint8_t* source, uint32_t width, uint32_t height, int degrees, uint8_t* destination)
        {
            const auto* src = reinterpret_cast<const uint32_t*>(source);
            auto* dst = reinterpret_cast<uint32_t*>(destination);

            if (degrees == 180)
            {
                const size_t count = static_cast<size_t>(width) * height;
                for (size_t i = 0; i < count; ++i) dst[i] = src[count - 1 - i];
                return;
            }

            if (degrees == 90)
            {
                for (uint32_t r = 0; r < width; ++r)
                    for (uint32_t c = 0; c < height; ++c)
                        dst[static_cast<size_t>(r) * height + c] = src[static_cast<size_t>(height - 1 - c) * width + r];
                return;
            }

            for (uint32_t r = 0; r < width; ++r)
                for (uint32_t c = 0; c < height; ++c)
                    dst[static_cast<size_t>(r) * height + c] = src[static_cast<size_t>(c) * width + (width - 1 - r)];
        }
    }

    Renderer::Renderer(FrameBridge::Writer& writer, uint32_t forcedWidth, uint32_t forcedHeight)
        : writer_(writer)
    {
        uint32_t width = 0;
        uint32_t height = 0;
        if (forcedWidth > 0 && forcedHeight > 0)
            LockOutput(forcedWidth, forcedHeight);
        else if (FrameBridge::PeekSize(width, height))
            LockOutput(width, height);
    }

    Renderer::~Renderer()
    {
        if (convert_) sws_freeContext(convert_);
        if (scale_) sws_freeContext(scale_);
    }

    void Renderer::SetRotation(int degrees)
    {
        rotation_ = ((degrees % 360) + 360) % 360;
    }

    void Renderer::LockOutput(uint32_t width, uint32_t height)
    {
        if (width == 0 || height == 0) return;
        const double fit = std::min({1.0,
                                     static_cast<double>(FrameBridge::MaxWidth) / width,
                                     static_cast<double>(FrameBridge::MaxHeight) / height});
        lockedWidth_ = std::max<uint32_t>(2, static_cast<uint32_t>(width * fit));
        lockedHeight_ = std::max<uint32_t>(2, static_cast<uint32_t>(height * fit));
    }

    bool Renderer::Present(const AVFrame& frame)
    {
        const int srcWidth = frame.width;
        const int srcHeight = frame.height;
        if (srcWidth <= 0 || srcHeight <= 0 || frame.format < 0) return false;

        convert_ = sws_getCachedContext(
            convert_, srcWidth, srcHeight, static_cast<AVPixelFormat>(frame.format),
            srcWidth, srcHeight, AV_PIX_FMT_BGRA, SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (!convert_) return false;

        sws_setColorspaceDetails(
            convert_, sws_getCoefficients(Coefficients(frame)),
            frame.color_range == AVCOL_RANGE_JPEG ? 1 : 0,
            sws_getCoefficients(SWS_CS_DEFAULT), 1, 0, 1 << 16, 1 << 16);

        const size_t convertedBytes = static_cast<size_t>(srcWidth) * srcHeight * 4;
        converted_.resize(convertedBytes);
        uint8_t* convertedPlanes[4] = {converted_.data(), nullptr, nullptr, nullptr};
        const int convertedStrides[4] = {srcWidth * 4, 0, 0, 0};
        sws_scale(convert_, frame.data, frame.linesize, 0, srcHeight, convertedPlanes, convertedStrides);

        uint32_t outWidth = static_cast<uint32_t>(srcWidth);
        uint32_t outHeight = static_cast<uint32_t>(srcHeight);
        const uint8_t* pixels = converted_.data();

        if (rotation_ != 0)
        {
            rotated_.resize(convertedBytes);
            Rotate(converted_.data(), outWidth, outHeight, rotation_, rotated_.data());
            if (rotation_ != 180) std::swap(outWidth, outHeight);
            pixels = rotated_.data();
        }

        if (lockedWidth_ == 0) LockOutput(outWidth, outHeight);

        if (outWidth == lockedWidth_ && outHeight == lockedHeight_)
            return writer_.Write(pixels, outWidth, outHeight);

        const double factor = std::min(static_cast<double>(lockedWidth_) / outWidth,
                                       static_cast<double>(lockedHeight_) / outHeight);
        const int fitWidth = std::max(1, static_cast<int>(outWidth * factor));
        const int fitHeight = std::max(1, static_cast<int>(outHeight * factor));
        const int offsetX = (static_cast<int>(lockedWidth_) - fitWidth) / 2;
        const int offsetY = (static_cast<int>(lockedHeight_) - fitHeight) / 2;

        scale_ = sws_getCachedContext(
            scale_, static_cast<int>(outWidth), static_cast<int>(outHeight), AV_PIX_FMT_BGRA,
            fitWidth, fitHeight, AV_PIX_FMT_BGRA, SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (!scale_) return false;

        canvas_.resize(static_cast<size_t>(lockedWidth_) * lockedHeight_ * 4);
        auto* canvas32 = reinterpret_cast<uint32_t*>(canvas_.data());
        std::fill(canvas32, canvas32 + static_cast<size_t>(lockedWidth_) * lockedHeight_, 0xFF000000u);

        const uint8_t* scaleSource[4] = {pixels, nullptr, nullptr, nullptr};
        const int scaleSourceStrides[4] = {static_cast<int>(outWidth) * 4, 0, 0, 0};
        uint8_t* scaleTarget[4] = {
            canvas_.data() + (static_cast<size_t>(offsetY) * lockedWidth_ + offsetX) * 4, nullptr, nullptr, nullptr};
        const int scaleTargetStrides[4] = {static_cast<int>(lockedWidth_) * 4, 0, 0, 0};
        sws_scale(scale_, scaleSource, scaleSourceStrides, 0, static_cast<int>(outHeight), scaleTarget, scaleTargetStrides);

        return writer_.Write(canvas_.data(), lockedWidth_, lockedHeight_);
    }
}
