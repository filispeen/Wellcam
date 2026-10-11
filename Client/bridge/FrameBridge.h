#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <sddl.h>
#pragma comment(lib, "advapi32.lib")
#include <cstdint>
#include <cstring>
#include <mutex>

namespace FrameBridge
{
    constexpr uint32_t MaxWidth = 3840;
    constexpr uint32_t MaxHeight = 2160;
    constexpr uint32_t BytesPerPixel = 4;
    constexpr uint32_t MaxFrameBytes = MaxWidth * MaxHeight * BytesPerPixel;
    constexpr uint32_t DefaultWidth = 1280;
    constexpr uint32_t DefaultHeight = 720;

    struct Header
    {
        uint32_t magic;
        uint32_t width;
        uint32_t height;
        uint32_t bytes;
        uint64_t sequence;
    };

    constexpr uint32_t Magic = 0x324D4356;
    constexpr size_t MappingBytes = sizeof(Header) + MaxFrameBytes;
    constexpr wchar_t MappingName[] = L"Global\\CppVirtualCamera.Frame";
    constexpr wchar_t MutexName[] = L"Global\\CppVirtualCamera.FrameMutex";
    constexpr wchar_t Sddl[] = L"D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;LS)(A;;GA;;;AU)";

    class Writer
    {
    public:
        Writer() = default;
        Writer(const Writer&) = delete;
        Writer& operator=(const Writer&) = delete;
        ~Writer() { Close(); }

        bool Open()
        {
            if (view_) return true;

            PSECURITY_DESCRIPTOR descriptor = nullptr;
            if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    Sddl, SDDL_REVISION_1, &descriptor, nullptr))
                return false;

            SECURITY_ATTRIBUTES attributes{};
            attributes.nLength = sizeof(attributes);
            attributes.lpSecurityDescriptor = descriptor;
            attributes.bInheritHandle = FALSE;

            mapping_ = CreateFileMappingW(
                INVALID_HANDLE_VALUE, &attributes, PAGE_READWRITE,
                static_cast<DWORD>(static_cast<uint64_t>(MappingBytes) >> 32),
                static_cast<DWORD>(MappingBytes & 0xFFFFFFFFu), MappingName);
            if (mapping_)
                mutex_ = CreateMutexW(&attributes, FALSE, MutexName);
            LocalFree(descriptor);

            if (!mapping_ || !mutex_) { Release(); return false; }

            view_ = static_cast<uint8_t*>(MapViewOfFile(
                mapping_, FILE_MAP_ALL_ACCESS, 0, 0, MappingBytes));
            if (!view_) { Release(); return false; }
            return true;
        }

        bool Write(const uint8_t* pixels, uint32_t width, uint32_t height)
        {
            if (!view_ || !pixels) return false;
            if (width == 0 || height == 0 || width > MaxWidth || height > MaxHeight) return false;

            const uint32_t bytes = width * height * BytesPerPixel;
            const DWORD wait = WaitForSingleObject(mutex_, 100);
            if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return false;

            auto* header = reinterpret_cast<Header*>(view_);
            std::memcpy(view_ + sizeof(Header), pixels, bytes);
            header->magic = Magic;
            header->width = width;
            header->height = height;
            header->bytes = bytes;
            ++header->sequence;
            ReleaseMutex(mutex_);
            return true;
        }

        void Clear()
        {
            if (!view_ || !mutex_) return;
            const DWORD wait = WaitForSingleObject(mutex_, 100);
            if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return;
            auto* header = reinterpret_cast<Header*>(view_);
            header->bytes = 0;
            ++header->sequence;
            ReleaseMutex(mutex_);
        }

        void Close()
        {
            Clear();
            Release();
        }

        bool IsOpen() const { return view_ != nullptr; }

    private:
        void Release()
        {
            if (view_) { UnmapViewOfFile(view_); view_ = nullptr; }
            if (mutex_) { CloseHandle(mutex_); mutex_ = nullptr; }
            if (mapping_) { CloseHandle(mapping_); mapping_ = nullptr; }
        }

        HANDLE mapping_ = nullptr;
        HANDLE mutex_ = nullptr;
        uint8_t* view_ = nullptr;
    };

    class Reader
    {
    public:
        Reader() = default;
        Reader(const Reader&) = delete;
        Reader& operator=(const Reader&) = delete;
        ~Reader() { Release(); }

        bool PeekSize(uint32_t& width, uint32_t& height)
        {
            if (!Ensure()) return false;
            const DWORD wait = WaitForSingleObject(mutex_, 5);
            if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return false;

            const auto* header = reinterpret_cast<const Header*>(view_);
            const bool ok = header->magic == Magic && header->width != 0 && header->height != 0 &&
                            header->width <= MaxWidth && header->height <= MaxHeight;
            if (ok) { width = header->width; height = header->height; }
            ReleaseMutex(mutex_);
            return ok;
        }

        bool Read(uint8_t* destination, uint32_t capacity, uint32_t& width, uint32_t& height)
        {
            if (!destination || !Ensure()) return false;
            const DWORD wait = WaitForSingleObject(mutex_, 5);
            if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) return false;

            const auto* header = reinterpret_cast<const Header*>(view_);
            bool ok = Valid(*header) && header->bytes <= capacity;
            if (ok)
            {
                std::memcpy(destination, view_ + sizeof(Header), header->bytes);
                width = header->width;
                height = header->height;
            }
            ReleaseMutex(mutex_);
            return ok;
        }

    private:
        static bool Valid(const Header& header)
        {
            return header.magic == Magic && header.width != 0 && header.height != 0 &&
                   header.width <= MaxWidth && header.height <= MaxHeight &&
                   header.bytes == header.width * header.height * BytesPerPixel;
        }

        bool Ensure()
        {
            if (view_) return true;
            mapping_ = OpenFileMappingW(FILE_MAP_READ, FALSE, MappingName);
            if (!mapping_) return false;
            mutex_ = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, MutexName);
            if (!mutex_) { Release(); return false; }
            view_ = static_cast<const uint8_t*>(MapViewOfFile(
                mapping_, FILE_MAP_READ, 0, 0, MappingBytes));
            if (!view_) { Release(); return false; }
            return true;
        }

        void Release()
        {
            if (view_) { UnmapViewOfFile(view_); view_ = nullptr; }
            if (mutex_) { CloseHandle(mutex_); mutex_ = nullptr; }
            if (mapping_) { CloseHandle(mapping_); mapping_ = nullptr; }
        }

        HANDLE mapping_ = nullptr;
        HANDLE mutex_ = nullptr;
        const uint8_t* view_ = nullptr;
    };

    inline bool PeekSize(uint32_t& width, uint32_t& height)
    {
        static std::mutex guard;
        static Reader reader;
        std::lock_guard<std::mutex> lock(guard);
        return reader.PeekSize(width, height);
    }

    inline bool ReadBGRA(uint8_t* destination, uint32_t capacity, uint32_t& width, uint32_t& height)
    {
        static std::mutex guard;
        static Reader reader;
        std::lock_guard<std::mutex> lock(guard);
        return reader.Read(destination, capacity, width, height);
    }
}
