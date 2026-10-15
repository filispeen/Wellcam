#include "transport.h"

#include <winsock2.h>
#include <ws2tcpip.h>

#include <mutex>

#pragma comment(lib, "ws2_32.lib")

namespace wellcam
{
    namespace
    {
        void EnsureWinsock()
        {
            static std::once_flag once;
            std::call_once(once, [] {
                WSADATA data{};
                WSAStartup(MAKEWORD(2, 2), &data);
            });
        }

        constexpr int ConnectTimeoutMs = 2000;
        constexpr int ReadTimeoutMs = 500;
        constexpr int WriteTimeoutMs = 1000;

        class TcpTransport : public Transport
        {
        public:
            TcpTransport(std::string host, std::string port)
                : host_(std::move(host)), port_(std::move(port))
            {
                EnsureWinsock();
            }

            ~TcpTransport() override { Close(); }

            const char* Name() const override { return "TCP"; }

            bool Open() override
            {
                Close();

                addrinfo hints{};
                hints.ai_family = AF_UNSPEC;
                hints.ai_socktype = SOCK_STREAM;
                hints.ai_protocol = IPPROTO_TCP;
                addrinfo* results = nullptr;
                if (getaddrinfo(host_.c_str(), port_.c_str(), &hints, &results) != 0) return false;

                for (addrinfo* ai = results; ai != nullptr; ai = ai->ai_next)
                {
                    SOCKET s = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
                    if (s == INVALID_SOCKET) continue;

                    u_long nonBlocking = 1;
                    ioctlsocket(s, FIONBIO, &nonBlocking);

                    const int rc = connect(s, ai->ai_addr, static_cast<int>(ai->ai_addrlen));
                    bool connected = rc == 0;
                    if (!connected && WSAGetLastError() == WSAEWOULDBLOCK)
                    {
                        fd_set writable;
                        fd_set failed;
                        FD_ZERO(&writable);
                        FD_ZERO(&failed);
                        FD_SET(s, &writable);
                        FD_SET(s, &failed);
                        timeval timeout{ConnectTimeoutMs / 1000, (ConnectTimeoutMs % 1000) * 1000};
                        if (select(0, nullptr, &writable, &failed, &timeout) > 0 && FD_ISSET(s, &writable))
                        {
                            int error = 0;
                            int length = sizeof(error);
                            getsockopt(s, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&error), &length);
                            connected = error == 0;
                        }
                    }

                    if (connected)
                    {
                        int bufferSize = 1 << 20;
                        setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<const char*>(&bufferSize), sizeof(bufferSize));
                        socket_ = s;
                        freeaddrinfo(results);
                        return true;
                    }
                    closesocket(s);
                }

                freeaddrinfo(results);
                return false;
            }

            int Read(uint8_t* buffer, int capacity) override
            {
                if (socket_ == INVALID_SOCKET) return -1;

                fd_set readable;
                FD_ZERO(&readable);
                FD_SET(socket_, &readable);
                timeval timeout{ReadTimeoutMs / 1000, (ReadTimeoutMs % 1000) * 1000};
                const int ready = select(0, &readable, nullptr, nullptr, &timeout);
                if (ready == 0) return 0;
                if (ready < 0) return -1;

                const int received = recv(socket_, reinterpret_cast<char*>(buffer), capacity, 0);
                if (received > 0) return received;
                if (received == 0) return -1;
                return WSAGetLastError() == WSAEWOULDBLOCK ? 0 : -1;
            }

            bool Write(const uint8_t* data, int size) override
            {
                if (socket_ == INVALID_SOCKET) return false;

                int sent = 0;
                while (sent < size)
                {
                    const int n = send(socket_, reinterpret_cast<const char*>(data + sent), size - sent, 0);
                    if (n > 0)
                    {
                        sent += n;
                        continue;
                    }
                    if (n < 0 && WSAGetLastError() == WSAEWOULDBLOCK)
                    {
                        fd_set writable;
                        FD_ZERO(&writable);
                        FD_SET(socket_, &writable);
                        timeval timeout{WriteTimeoutMs / 1000, (WriteTimeoutMs % 1000) * 1000};
                        if (select(0, nullptr, &writable, nullptr, &timeout) > 0) continue;
                    }
                    return false;
                }
                return true;
            }

            void Close() override
            {
                if (socket_ != INVALID_SOCKET)
                {
                    shutdown(socket_, SD_BOTH);
                    closesocket(socket_);
                    socket_ = INVALID_SOCKET;
                }
            }

        private:
            std::string host_;
            std::string port_;
            SOCKET socket_ = INVALID_SOCKET;
        };
    }

    std::unique_ptr<Transport> MakeTcpTransport(const std::string& host, const std::string& port)
    {
        return std::make_unique<TcpTransport>(host, port);
    }
}
