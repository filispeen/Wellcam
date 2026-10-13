#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <sddl.h>
#pragma comment(lib, "advapi32.lib")
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace FrameBridge
{
    constexpr uint32_t MaxWidth = 3840;
    constexpr uint32_t MaxHeight = 2160;
    constexpr uint32_t BytesPerPixel = 4;
    constexpr uint32_t MaxFrameBytes = MaxWidth * MaxHeight * BytesPerPixel;
    constexpr uint32_t DefaultWidth = 1920;
    constexpr uint32_t DefaultHeight = 1080;

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
    constexpr wchar_t Sddl[] = L"D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;LS)(A;;GA;;;AU)";
    constexpr wchar_t TracePath[] = L"C:\\ProgramData\\WinCamBridge.log";

    inline bool& TraceEnabled()
    {
        static bool enabled = false;
        return enabled;
    }

    inline void Trace(const char* text, DWORD code = 0)
    {
        if (!TraceEnabled()) return;
        SECURITY_ATTRIBUTES attributes{};
        attributes.nLength = sizeof(attributes);
        PSECURITY_DESCRIPTOR descriptor = nullptr;
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(L"D:(A;;GA;;;WD)", SDDL_REVISION_1, &descriptor, nullptr))
        {
            attributes.lpSecurityDescriptor = descriptor;
        }
        HANDLE file = CreateFileW(TracePath, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                  descriptor ? &attributes : nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (descriptor) LocalFree(descriptor);
        if (file == INVALID_HANDLE_VALUE) return;
        SYSTEMTIME time;
        GetLocalTime(&time);
        char line[512];
        const int length = std::snprintf(line, sizeof(line), "%02u:%02u:%02u.%03u pid=%lu %s code=%lu\r\n",
                                         time.wHour, time.wMinute, time.wSecond, time.wMilliseconds,
                                         GetCurrentProcessId(), text, code);
        if (length > 0)
        {
            DWORD written = 0;
            WriteFile(file, line, static_cast<DWORD>(length), &written, nullptr);
        }
        CloseHandle(file);
    }

    inline uint64_t LoadSequence(const Header* header)
    {
        const uint64_t value = *reinterpret_cast<const volatile uint64_t*>(&header->sequence);
        MemoryBarrier();
        return value;
    }

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
            LocalFree(descriptor);
            if (!mapping_) return false;

            view_ = static_cast<uint8_t*>(MapViewOfFile(
                mapping_, FILE_MAP_ALL_ACCESS, 0, 0, MappingBytes));
            if (!view_) { Release(); return false; }

            auto* header = reinterpret_cast<Header*>(view_);
            if (LoadSequence(header) & 1) InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
            return true;
        }

        bool Write(const uint8_t* pixels, uint32_t width, uint32_t height)
        {
            if (!view_ || !pixels) return false;
            if (width == 0 || height == 0 || width > MaxWidth || height > MaxHeight) return false;

            const uint32_t bytes = width * height * BytesPerPixel;
            auto* header = reinterpret_cast<Header*>(view_);

            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
            std::memcpy(view_ + sizeof(Header), pixels, bytes);
            header->magic = Magic;
            header->width = width;
            header->height = height;
            header->bytes = bytes;
            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
            return true;
        }

        void Announce(uint32_t width, uint32_t height)
        {
            if (!view_ || width == 0 || height == 0 || width > MaxWidth || height > MaxHeight) return;
            auto* header = reinterpret_cast<Header*>(view_);
            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
            header->magic = Magic;
            header->width = width;
            header->height = height;
            header->bytes = 0;
            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
        }

        void Clear()
        {
            if (!view_) return;
            auto* header = reinterpret_cast<Header*>(view_);
            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
            header->bytes = 0;
            InterlockedIncrement64(reinterpret_cast<volatile LONG64*>(&header->sequence));
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
            if (mapping_) { CloseHandle(mapping_); mapping_ = nullptr; }
        }

        HANDLE mapping_ = nullptr;
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
            const auto* header = reinterpret_cast<const Header*>(view_);

            for (int attempt = 0; attempt < 8; ++attempt)
            {
                const uint64_t before = LoadSequence(header);
                if (before & 1) { Sleep(1); continue; }
                const uint32_t magic = header->magic;
                const uint32_t w = header->width;
                const uint32_t h = header->height;
                MemoryBarrier();
                if (LoadSequence(header) != before) continue;

                const bool ok = magic == Magic && w != 0 && h != 0 && w <= MaxWidth && h <= MaxHeight;
                if (ok) { width = w; height = h; }
                return ok;
            }
            return false;
        }

        bool Read(uint8_t* destination, uint32_t capacity, uint32_t& width, uint32_t& height)
        {
            if (!destination || !Ensure()) return false;
            const auto* header = reinterpret_cast<const Header*>(view_);

            for (int attempt = 0; attempt < 6; ++attempt)
            {
                const uint64_t before = LoadSequence(header);
                if (before & 1) { Sleep(1); continue; }

                const uint32_t magic = header->magic;
                const uint32_t w = header->width;
                const uint32_t h = header->height;
                const uint32_t bytes = header->bytes;
                const bool valid = magic == Magic && w != 0 && h != 0 && w <= MaxWidth && h <= MaxHeight &&
                                   bytes == w * h * BytesPerPixel && bytes <= capacity;
                if (valid) std::memcpy(destination, view_ + sizeof(Header), bytes);
                MemoryBarrier();
                if (LoadSequence(header) != before) { Sleep(1); continue; }

                if (!valid) { Note(3, "bridge has no frame", bytes); return false; }
                width = w;
                height = h;
                Note(4, "bridge frames flowing", w);
                return true;
            }

            Note(5, "bridge read kept tearing", 0);
            return false;
        }

    private:
        void Note(int state, const char* text, DWORD code)
        {
            if (state == state_) return;
            state_ = state;
            Trace(text, code);
        }

        bool Ensure()
        {
            if (view_) return true;
            mapping_ = OpenFileMappingW(FILE_MAP_READ, FALSE, MappingName);
            if (!mapping_)
            {
                Note(1, "OpenFileMapping failed", GetLastError());
                return false;
            }
            view_ = static_cast<const uint8_t*>(MapViewOfFile(
                mapping_, FILE_MAP_READ, 0, 0, MappingBytes));
            if (!view_)
            {
                Note(2, "MapViewOfFile failed", GetLastError());
                Release();
                return false;
            }
            Note(6, "bridge mapping opened", 0);
            return true;
        }

        void Release()
        {
            if (view_) { UnmapViewOfFile(view_); view_ = nullptr; }
            if (mapping_) { CloseHandle(mapping_); mapping_ = nullptr; }
        }

        HANDLE mapping_ = nullptr;
        const uint8_t* view_ = nullptr;
        int state_ = 0;
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
