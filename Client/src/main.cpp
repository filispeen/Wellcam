#include <windows.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "FrameBridge.h"
#include "control.h"
#include "decoder.h"
#include "protocol.h"
#include "renderer.h"
#include "transport.h"
#include "vcam.h"

namespace
{
    std::atomic<bool> g_running{true};

    BOOL WINAPI OnConsoleEvent(DWORD)
    {
        g_running = false;
        return TRUE;
    }

    struct Options
    {
        bool aoa = false;
        std::string host = "127.0.0.1";
        std::string port = "11203";
        int vendor = -1;
        int product = -1;
        int offset = 180;
        uint32_t width = 0;
        uint32_t height = 0;
        wellcam::DecoderMode decoder = wellcam::DecoderMode::Auto;
        int gpu = 0;
        bool virtualCamera = true;
    };

    void PrintUsage()
    {
        std::printf(
            "Usage: wellcam_connect [options]\n"
            "  --tcp host:port     TCP transport (default 127.0.0.1:11203)\n"
            "  --aoa               USB accessory transport\n"
            "  --vid HEX           phone vendor id filter for --aoa\n"
            "  --pid HEX           phone product id filter for --aoa\n"
            "  --offset DEGREES    rotation added to the header rotation (default 180)\n"
            "  --size WxH          camera output size, stream is letterboxed into it (default 1920x1080)\n"
            "  --decoder MODE      auto (default), cpu or gpu (D3D11VA, any vendor)\n"
            "  --gpu INDEX         adapter index for GPU decoding (default 0)\n"
            "  --no-vcam           do not create the virtual camera, only fill the frame bridge\n"
            "Type commands and press Enter: help, state, show, camera, resolution, fps, codec, cbr, bitrate, shutter, iso, r (rotate output by 90 degrees), q (quit)\n"
            "Run from an elevated terminal.\n");
    }

    bool ParseArgs(int argc, char** argv, Options& options)
    {
        for (int i = 1; i < argc; ++i)
        {
            const std::string arg = argv[i];
            const bool hasValue = i + 1 < argc;

            if (arg == "--aoa")
            {
                options.aoa = true;
            }
            else if (arg == "--no-vcam")
            {
                options.virtualCamera = false;
            }
            else if (arg == "--tcp" && hasValue)
            {
                const std::string value = argv[++i];
                const size_t colon = value.rfind(':');
                if (colon == std::string::npos)
                {
                    options.host = value;
                }
                else
                {
                    options.host = value.substr(0, colon);
                    options.port = value.substr(colon + 1);
                }
                options.aoa = false;
            }
            else if (arg == "--vid" && hasValue)
            {
                options.vendor = static_cast<int>(std::strtol(argv[++i], nullptr, 16));
            }
            else if (arg == "--pid" && hasValue)
            {
                options.product = static_cast<int>(std::strtol(argv[++i], nullptr, 16));
            }
            else if (arg == "--offset" && hasValue)
            {
                options.offset = std::atoi(argv[++i]);
            }
            else if (arg == "--decoder" && hasValue)
            {
                const std::string value = argv[++i];
                if (value == "auto") options.decoder = wellcam::DecoderMode::Auto;
                else if (value == "cpu") options.decoder = wellcam::DecoderMode::Cpu;
                else if (value == "gpu") options.decoder = wellcam::DecoderMode::Gpu;
                else return false;
            }
            else if (arg == "--gpu" && hasValue)
            {
                options.gpu = std::atoi(argv[++i]);
            }
            else if (arg == "--size" && hasValue)
            {
                unsigned int w = 0;
                unsigned int h = 0;
                if (std::sscanf(argv[++i], "%ux%u", &w, &h) != 2 || w == 0 || h == 0) return false;
                options.width = w;
                options.height = h;
            }
            else
            {
                return false;
            }
        }
        return true;
    }

    using Clock = std::chrono::steady_clock;

    double SecondsSince(Clock::time_point from)
    {
        return std::chrono::duration<double>(Clock::now() - from).count();
    }
}

int main(int argc, char** argv)
{
    Options options;
    if (!ParseArgs(argc, argv, options))
    {
        PrintUsage();
        return 2;
    }

    SetConsoleCtrlHandler(OnConsoleEvent, TRUE);

    FrameBridge::Writer writer;
    if (!writer.Open())
    {
        std::fprintf(stderr, "Cannot open the shared frame bridge (error %lu). Run from an elevated terminal.\n",
                     GetLastError());
        return 1;
    }

    wellcam::Renderer renderer(writer, options.width, options.height);
    writer.Announce(renderer.OutputWidth(), renderer.OutputHeight());

    wellcam::VirtualCamera camera;
    if (options.virtualCamera)
    {
        const long result = camera.Start(L"Wellcam Connect");
        if (result < 0)
        {
            std::fprintf(stderr,
                         "Cannot start the virtual camera (0x%08lX). Register VCamSampleSource.dll with regsvr32 "
                         "from an elevated terminal and close VCamSample.exe if it is running.\n",
                         static_cast<unsigned long>(result));
            return 1;
        }
        std::printf("Virtual camera \"Wellcam Connect\" started.\n");
    }

    std::unique_ptr<wellcam::Transport> transport = options.aoa
        ? wellcam::MakeAoaTransport(options.vendor, options.product)
        : wellcam::MakeTcpTransport(options.host, options.port);

    wellcam::Decoder decoder;
    wellcam::Controller controller;
    static std::mutex inputMutex;
    static std::deque<std::string> inputLines;
    std::thread([] {
        std::string line;
        while (std::getline(std::cin, line))
        {
            std::lock_guard<std::mutex> lock(inputMutex);
            inputLines.push_back(line);
        }
    }).detach();
    int headerRotation = 0;
    int phoneRotation = 0;
    int extraRotation = 0;
    uint64_t frames = 0;
    bool reportedOutput = false;
    bool gotHeader = false;

    wellcam::StreamParser parser(
        [&](const wellcam::StreamHeader& header) {
            headerRotation = header.rotation;
            reportedOutput = false;
            gotHeader = true;
            std::printf("Stream: %s %ux%u %u fps rotation %u\n",
                        header.codec == wellcam::Codec::H265 ? "H.265" : "H.264",
                        header.width, header.height, header.fps, header.rotation);
            if (!decoder.Open(header.codec, options.decoder, options.gpu)) std::printf("Decoder open failed\n");
        },
        [&](const wellcam::Packet& packet) {
            if (packet.type == wellcam::PacketType::State)
            {
                if (controller.OnState(packet.data, packet.size))
                    std::printf("Phone settings received. Type show or help.\n");
                return;
            }
            phoneRotation = ((2 - packet.orientation) & 3) * 90;
            decoder.Decode(packet.data, packet.size, static_cast<int64_t>(packet.pts),
                           [&](const AVFrame& frame) {
                               renderer.SetRotation(headerRotation + options.offset + phoneRotation + extraRotation);
                               if (renderer.Present(frame))
                               {
                                   ++frames;
                                   if (!reportedOutput)
                                   {
                                       reportedOutput = true;
                                       std::printf("Camera output: %ux%u, decoding on %s\n",
                                                   renderer.OutputWidth(), renderer.OutputHeight(),
                                                   decoder.LastFrameHardware() ? "GPU" : "CPU");
                                   }
                               }
                           });
        });

    std::printf("wellcam_connect: %s transport. Type help for commands, q to quit.\n", transport->Name());

    std::vector<uint8_t> buffer(65536);
    bool announcedWaiting = false;

    while (g_running)
    {
        if (!transport->Open())
        {
            if (!announcedWaiting)
            {
                std::printf("Waiting for phone...\n");
                announcedWaiting = true;
            }
            for (int i = 0; i < 10 && g_running; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
        }

        announcedWaiting = false;
        parser.Reset();
        controller.Reset();
        gotHeader = false;
        {
            std::lock_guard<std::mutex> lock(inputMutex);
            inputLines.clear();
        }
        {
            const std::vector<uint8_t> request = controller.StateRequest();
            if (!transport->Write(request.data(), static_cast<int>(request.size())))
                std::printf("Cannot send the settings request\n");
        }
        auto lastData = Clock::now();
        auto lastStats = Clock::now();
        uint64_t statsFrames = frames;

        while (g_running)
        {
            for (;;)
            {
                std::string line;
                {
                    std::lock_guard<std::mutex> lock(inputMutex);
                    if (inputLines.empty()) break;
                    line = inputLines.front();
                    inputLines.pop_front();
                }
                if (line == "q" || line == "Q" || line == "quit")
                {
                    g_running = false;
                }
                else if (line == "r" || line == "R")
                {
                    extraRotation = (extraRotation + 90) % 360;
                    std::printf("Extra rotation: %d\n", extraRotation);
                }
                else
                {
                    std::vector<uint8_t> out;
                    std::string message;
                    controller.Handle(line, out, message);
                    if (!message.empty()) std::printf("%s\n", message.c_str());
                    if (!out.empty() && !transport->Write(out.data(), static_cast<int>(out.size())))
                        std::printf("Send failed\n");
                }
            }
            if (!g_running) break;

            const int received = transport->Read(buffer.data(), static_cast<int>(buffer.size()));
            if (received < 0) break;

            if (received == 0)
            {
                if (SecondsSince(lastData) > 4.0)
                {
                    parser.Reset();
                    transport->Resync();
                    lastData = Clock::now();
                }
            }
            else
            {
                lastData = Clock::now();
                parser.Feed(buffer.data(), static_cast<size_t>(received));
            }

            if (SecondsSince(lastStats) >= 5.0)
            {
                const double fps = static_cast<double>(frames - statsFrames) / SecondsSince(lastStats);
                std::printf("%.1f fps, skipped %zu bytes\n", fps, parser.SkippedBytes());
                statsFrames = frames;
                lastStats = Clock::now();
            }
        }

        transport->Close();
        decoder.Close();
        writer.Clear();
        if (g_running)
        {
            if (gotHeader) std::printf("Disconnected\n");
            for (int i = 0; i < 5 && g_running; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }

    camera.Stop();
    writer.Close();
    return 0;
}
