#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

namespace wellcam
{
    class Transport
    {
    public:
        virtual ~Transport() = default;

        virtual const char* Name() const = 0;
        virtual bool Open() = 0;
        virtual int Read(uint8_t* buffer, int capacity) = 0;
        virtual bool Write(const uint8_t* data, int size) = 0;
        virtual void Resync() {}
        virtual void Close() = 0;
    };

    std::unique_ptr<Transport> MakeTcpTransport(const std::string& host, const std::string& port);
    std::unique_ptr<Transport> MakeAoaTransport(int vendorFilter, int productFilter);
}
