#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

namespace wellcam
{
    class Controller
    {
    public:
        void Reset();
        bool OnState(const uint8_t* data, size_t size);
        std::vector<uint8_t> StateRequest() const;
        void Handle(const std::string& line, std::vector<uint8_t>& out, std::string& message);

        static const char* Help();

    private:
        std::string Summary() const;
        bool InList(const char* key, const std::string& value) const;
        bool HasResolution(const std::string& value) const;
        bool HasFps(long long value) const;

        nlohmann::json state_;
        bool hasState_ = false;
    };
}
