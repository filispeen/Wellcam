#include "control.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <sstream>

#include "protocol.h"

namespace wellcam
{
    namespace
    {
        std::vector<std::string> Split(const std::string& line)
        {
            std::istringstream stream(line);
            std::vector<std::string> tokens;
            std::string token;
            while (stream >> token) tokens.push_back(token);
            return tokens;
        }

        std::string Lower(std::string text)
        {
            for (char& c : text)
            {
                if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
            }
            return text;
        }

        std::string Join(const std::vector<std::string>& tokens, size_t from)
        {
            std::string result;
            for (size_t i = from; i < tokens.size(); ++i)
            {
                if (i > from) result += ' ';
                result += tokens[i];
            }
            return result;
        }

        bool ParseInt(const std::string& text, long long& value)
        {
            char* end = nullptr;
            value = std::strtoll(text.c_str(), &end, 10);
            return end != text.c_str() && *end == '\0';
        }

        bool ParseShutter(const std::string& text, long long& ns)
        {
            const size_t slash = text.find('/');
            if (slash == std::string::npos) return ParseInt(text, ns) && ns > 0;
            long long numerator = 0;
            long long denominator = 0;
            if (!ParseInt(text.substr(0, slash), numerator) || !ParseInt(text.substr(slash + 1), denominator)) return false;
            if (numerator <= 0 || denominator <= 0) return false;
            ns = static_cast<long long>(std::llround(static_cast<double>(numerator) * 1e9 / static_cast<double>(denominator)));
            return ns > 0;
        }

        std::string ShutterText(long long ns)
        {
            char text[32];
            if (ns <= 0) return "?";
            if (ns >= 1000000000LL) std::snprintf(text, sizeof(text), "%.1fs", static_cast<double>(ns) / 1e9);
            else std::snprintf(text, sizeof(text), "1/%lld", static_cast<long long>(std::llround(1e9 / static_cast<double>(ns))));
            return text;
        }

        std::vector<uint8_t> Set(const nlohmann::json& json)
        {
            return BuildCommand(CommandSet, json.dump());
        }

        std::string Str(const nlohmann::json& object, const char* key)
        {
            if (object.contains(key) && object[key].is_string()) return object[key].get<std::string>();
            return "?";
        }

        long long Num(const nlohmann::json& object, const char* key)
        {
            if (object.contains(key) && object[key].is_number()) return object[key].get<long long>();
            return 0;
        }

        bool Flag(const nlohmann::json& object, const char* key)
        {
            return object.contains(key) && object[key].is_boolean() && object[key].get<bool>();
        }

        std::string JoinArray(const nlohmann::json& object, const char* key)
        {
            std::string result;
            if (!object.contains(key) || !object[key].is_array()) return result;
            for (const auto& item : object[key])
            {
                if (!result.empty()) result += ", ";
                result += item.is_string() ? item.get<std::string>() : item.dump();
            }
            return result;
        }
    }

    const char* Controller::Help()
    {
        return "Commands:\n"
               "  state                         request settings from the phone\n"
               "  show                          print the last received settings\n"
               "  camera NAME                   select camera\n"
               "  resolution WxH                select resolution\n"
               "  fps N                         select frame rate\n"
               "  codec H.264|H.265             select codec\n"
               "  cbr on|off                    constant or variable bitrate\n"
               "  bitrate auto                  automatic bitrate\n"
               "  bitrate MBPS MAX MIN          manual bitrate in Mbps\n"
               "  shutter auto|1/120|NS         shutter speed (NS in nanoseconds)\n"
               "  iso auto|N                    sensitivity\n"
               "  r                             rotate output by 90 degrees\n"
               "  q                             quit";
    }

    void Controller::Reset()
    {
        state_ = nlohmann::json();
        hasState_ = false;
    }

    bool Controller::OnState(const uint8_t* data, size_t size)
    {
        nlohmann::json parsed = nlohmann::json::parse(data, data + size, nullptr, false);
        if (parsed.is_discarded() || !parsed.is_object()) return false;
        const bool first = !hasState_;
        state_ = std::move(parsed);
        hasState_ = true;
        return first;
    }

    std::vector<uint8_t> Controller::StateRequest() const
    {
        return BuildCommand(CommandStateRequest, "{}");
    }

    bool Controller::InList(const char* key, const std::string& value) const
    {
        if (!hasState_ || !state_.contains(key) || !state_[key].is_array()) return true;
        for (const auto& item : state_[key])
        {
            if (item.is_string() && item.get<std::string>() == value) return true;
        }
        return false;
    }

    bool Controller::HasResolution(const std::string& value) const
    {
        if (!hasState_ || !state_.contains("resolutions") || !state_["resolutions"].is_array()) return true;
        for (const auto& group : state_["resolutions"])
        {
            if (!group.contains("sizes") || !group["sizes"].is_array()) continue;
            for (const auto& item : group["sizes"])
            {
                if (item.is_string() && item.get<std::string>() == value) return true;
            }
        }
        return false;
    }

    bool Controller::HasFps(long long value) const
    {
        if (!hasState_ || !state_.contains("fpsList") || !state_["fpsList"].is_array()) return true;
        for (const auto& item : state_["fpsList"])
        {
            if (item.is_number() && item.get<long long>() == value) return true;
        }
        return false;
    }

    std::string Controller::Summary() const
    {
        if (!hasState_) return "No settings received yet. Type state.";
        std::ostringstream out;
        out << "camera: " << Str(state_, "camera") << " (" << JoinArray(state_, "cameras") << ")\n";
        out << "resolution: " << Str(state_, "resolution") << "\n";
        out << "fps: " << Num(state_, "fps") << " (" << JoinArray(state_, "fpsList") << ")\n";
        out << "codec: " << Str(state_, "codec") << " (" << JoinArray(state_, "codecs") << ")\n";
        out << "bitrate: " << (Flag(state_, "autoBitrate") ? "auto " : "manual ") << Str(state_, "bitrate") << " max "
            << Str(state_, "max") << " min " << Str(state_, "min") << " Mbps, "
            << (Flag(state_, "constantBitrate") ? "CBR" : "VBR") << "\n";
        if (state_.contains("shutter") && state_["shutter"].is_object())
        {
            const auto& shutter = state_["shutter"];
            out << "shutter: " << (Flag(shutter, "manual") ? "manual " : "auto ") << ShutterText(Num(shutter, "ns"));
            if (shutter.contains("minNs") && shutter.contains("maxNs"))
                out << " (" << ShutterText(Num(shutter, "maxNs")) << " to " << ShutterText(Num(shutter, "minNs")) << ")";
            out << "\n";
        }
        if (state_.contains("iso") && state_["iso"].is_object())
        {
            const auto& iso = state_["iso"];
            out << "iso: " << (Flag(iso, "manual") ? "manual " : "auto ") << Num(iso, "value");
            if (iso.contains("min") && iso.contains("max")) out << " (" << Num(iso, "min") << " to " << Num(iso, "max") << ")";
        }
        return out.str();
    }

    void Controller::Handle(const std::string& line, std::vector<uint8_t>& out, std::string& message)
    {
        const std::vector<std::string> tokens = Split(line);
        if (tokens.empty()) return;
        const std::string command = Lower(tokens[0]);

        if (command == "help" || command == "?")
        {
            message = Help();
        }
        else if (command == "show")
        {
            message = Summary();
        }
        else if (command == "state")
        {
            out = StateRequest();
        }
        else if (command == "camera" && tokens.size() >= 2)
        {
            const std::string value = Join(tokens, 1);
            if (!InList("cameras", value)) message = "Unknown camera. Available: " + JoinArray(state_, "cameras");
            else out = Set({{"camera", value}});
        }
        else if (command == "resolution" && tokens.size() == 2)
        {
            if (!HasResolution(tokens[1])) message = "Unknown resolution. Type show for the list on the phone.";
            else out = Set({{"resolution", tokens[1]}});
        }
        else if (command == "fps" && tokens.size() == 2)
        {
            long long value = 0;
            if (!ParseInt(tokens[1], value)) message = "Usage: fps N";
            else if (!HasFps(value)) message = "Unsupported fps. Available: " + JoinArray(state_, "fpsList");
            else out = Set({{"fps", value}});
        }
        else if (command == "codec" && tokens.size() == 2)
        {
            std::string value = tokens[1];
            for (char& c : value)
            {
                if (c >= 'a' && c <= 'z') c = static_cast<char>(c - 'a' + 'A');
            }
            if (!InList("codecs", value)) message = "Unknown codec. Available: " + JoinArray(state_, "codecs");
            else out = Set({{"codec", value}});
        }
        else if (command == "cbr" && tokens.size() == 2 && (Lower(tokens[1]) == "on" || Lower(tokens[1]) == "off"))
        {
            out = Set({{"constantBitrate", Lower(tokens[1]) == "on"}});
        }
        else if (command == "bitrate" && tokens.size() == 2 && Lower(tokens[1]) == "auto")
        {
            out = Set({{"autoBitrate", true}});
        }
        else if (command == "bitrate" && tokens.size() == 4)
        {
            out = Set({{"autoBitrate", false}, {"bitrate", tokens[1]}, {"max", tokens[2]}, {"min", tokens[3]}});
        }
        else if (command == "shutter" && tokens.size() == 2)
        {
            long long ns = 0;
            if (Lower(tokens[1]) == "auto") out = Set({{"shutterManual", false}});
            else if (!ParseShutter(tokens[1], ns)) message = "Usage: shutter auto|1/120|NS";
            else out = Set({{"shutterManual", true}, {"shutterNs", ns}});
        }
        else if (command == "iso" && tokens.size() == 2)
        {
            long long value = 0;
            if (Lower(tokens[1]) == "auto") out = Set({{"isoManual", false}});
            else if (!ParseInt(tokens[1], value) || value <= 0) message = "Usage: iso auto|N";
            else out = Set({{"isoManual", true}, {"iso", value}});
        }
        else
        {
            message = "Unknown command. Type help.";
        }
    }
}
