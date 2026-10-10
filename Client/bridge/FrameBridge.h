#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <sddl.h>
#pragma comment(lib, "advapi32.lib")
#include <cstdint>
#include <cstring>
#include <string>

namespace FrameBridge
{
    constexpr uint32_t Width = 1280;
    constexpr uint32_t Height = 960;
    constexpr uint32_t BytesPerPixel = 4;
    constexpr uint32_t FrameBytes = Width * Height * BytesPerPixel;

    // Global namespace allows the camera service process to open the mapping.
    // ACL: LocalSystem, Administrators, and authenticated users.
    inline SECURITY_ATTRIBUTES* SecurityAttributes(SECURITY_DESCRIPTOR& sd)
    {
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                L"D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;AU)",
                SDDL_REVISION_1, &sd, nullptr))
            return nullptr;

        static SECURITY_ATTRIBUTES sa{};
        sa.nLength = sizeof(sa);
        sa.lpSecurityDescriptor = &sd;
        sa.bInheritHandle = FALSE;
        return &sa;
    }

    struct Header
    {
        uint32_t magic;
        uint32_t width;
        uint32_t height;
        uint32_t bytes;
        uint64_t sequence;
    };

    constexpr uint32_t Magic = 0x314D4356; // "VCM1"
    constexpr wchar_t MappingName[] = L"Global\\CppVirtualCamera.Frame";
    constexpr wchar_t MutexName[] = L"Global\\CppVirtualCamera.FrameMutex";

    inline bool WriteBGRA(const uint8_t* pixels, uint32_t width, uint32_t height)
    {
        if (!pixels || width != Width || height != Height)
            return false;

        SECURITY_DESCRIPTOR sd{};
        auto* sa = SecurityAttributes(sd);
        if (!sa) return false;

        HANDLE mapping = CreateFileMappingW(
            INVALID_HANDLE_VALUE, sa, PAGE_READWRITE, 0,
            static_cast<DWORD>(sizeof(Header) + FrameBytes), MappingName);
        LocalFree(sd);
        if (!mapping) return false;

        auto* view = static_cast<uint8_t*>(MapViewOfFile(
            mapping, FILE_MAP_ALL_ACCESS, 0, 0, sizeof(Header) + FrameBytes));
        if (!view) { CloseHandle(mapping); return false; }

        SECURITY_DESCRIPTOR msd{};
        auto* msa = SecurityAttributes(msd);
        if (!msa) { UnmapViewOfFile(view); CloseHandle(mapping); return false; }
        HANDLE mutex = CreateMutexW(msa, FALSE, MutexName);
        LocalFree(msd);
        if (!mutex) { UnmapViewOfFile(view); CloseHandle(mapping); return false; }

        const DWORD wait = WaitForSingleObject(mutex, 100);
        if (wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED)
        {
            auto* header = reinterpret_cast<Header*>(view);
            header->magic = Magic;
            header->width = Width;
            header->height = Height;
            header->bytes = FrameBytes;
            std::memcpy(view + sizeof(Header), pixels, FrameBytes);
            ++header->sequence;
            ReleaseMutex(mutex);
        }
        else
        {
            CloseHandle(mutex);
            UnmapViewOfFile(view);
            CloseHandle(mapping);
            return false;
        }

        CloseHandle(mutex);
        UnmapViewOfFile(view);
        CloseHandle(mapping);
        return true;
    }

    inline bool ReadBGRA(uint8_t* destination, uint32_t capacity)
    {
        if (!destination || capacity < FrameBytes) return false;

        HANDLE mapping = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, MappingName);
        if (!mapping) return false;

        auto* view = static_cast<uint8_t*>(MapViewOfFile(
            mapping, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, sizeof(Header) + FrameBytes));
        if (!view) { CloseHandle(mapping); return false; }

        HANDLE mutex = OpenMutexW(SYNCHRONIZE | MUTEX_MODIFY_STATE, FALSE, MutexName);
        if (!mutex) { UnmapViewOfFile(view); CloseHandle(mapping); return false; }

        bool ok = false;
        const DWORD wait = WaitForSingleObject(mutex, 5);
        if (wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED)
        {
            const auto* header = reinterpret_cast<const Header*>(view);
            if (header->magic == Magic && header->width == Width &&
                header->height == Height && header->bytes == FrameBytes)
            {
                std::memcpy(destination, view + sizeof(Header), FrameBytes);
                ok = true;
            }
            ReleaseMutex(mutex);
        }

        CloseHandle(mutex);
        UnmapViewOfFile(view);
        CloseHandle(mapping);
        return ok;
    }
}
