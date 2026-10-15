#include "transport.h"

#include <libusb.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

namespace wellcam
{
    namespace
    {
        constexpr uint16_t GoogleVendor = 0x18D1;
        constexpr uint16_t AccessoryProducts[] = {0x2D00, 0x2D01};
        constexpr int ControlTimeoutMs = 1000;
        constexpr int ReadTimeoutMs = 500;
        constexpr int WriteTimeoutMs = 1000;
        constexpr int AccessoryWaitMs = 10000;

        const char* const AccessoryStrings[] = {
            "Filispeen",
            "Wellcam",
            "Wellcam",
            "1",
            "https://example.invalid",
            "0"};

        constexpr uint8_t Hello[4] = {'H', 'E', 'L', 'O'};

        bool IsAccessoryProduct(uint16_t product)
        {
            return product == AccessoryProducts[0] || product == AccessoryProducts[1];
        }

        class AoaTransport : public Transport
        {
        public:
            AoaTransport(int vendorFilter, int productFilter)
                : vendorFilter_(vendorFilter), productFilter_(productFilter)
            {
                libusb_init(&context_);
            }

            ~AoaTransport() override
            {
                Close();
                if (context_) libusb_exit(context_);
            }

            const char* Name() const override { return "AOA"; }

            bool Open() override
            {
                Close();
                if (!context_) return false;

                handle_ = OpenAccessory();
                if (!handle_)
                {
                    if (!SwitchPhone()) return false;
                    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(AccessoryWaitMs);
                    while (!handle_ && std::chrono::steady_clock::now() < deadline)
                    {
                        std::this_thread::sleep_for(std::chrono::milliseconds(200));
                        handle_ = OpenAccessory();
                    }
                    if (!handle_) return false;
                }

                if (!Claim())
                {
                    Close();
                    return false;
                }

                if (!SendHello())
                {
                    Close();
                    return false;
                }
                return true;
            }

            int Read(uint8_t* buffer, int capacity) override
            {
                if (!handle_) return -1;

                const int size = capacity - (capacity % 512);
                if (size <= 0) return -1;

                int transferred = 0;
                const int rc = libusb_bulk_transfer(handle_, endpointIn_, buffer, size, &transferred, ReadTimeoutMs);
                if (rc == 0 || rc == LIBUSB_ERROR_TIMEOUT) return transferred > 0 ? transferred : 0;
                return -1;
            }

            bool Write(const uint8_t* data, int size) override
            {
                if (!handle_ || size <= 0) return false;

                std::vector<uint8_t> copy(data, data + size);
                int offset = 0;
                while (offset < size)
                {
                    int written = 0;
                    const int rc = libusb_bulk_transfer(handle_, endpointOut_, copy.data() + offset, size - offset,
                                                        &written, WriteTimeoutMs);
                    if (rc != 0 && !(rc == LIBUSB_ERROR_TIMEOUT && written > 0)) return false;
                    if (written <= 0) return false;
                    offset += written;
                }
                return true;
            }

            void Resync() override
            {
                if (handle_) SendHello();
            }

            void Close() override
            {
                if (handle_)
                {
                    if (claimed_ >= 0) libusb_release_interface(handle_, claimed_);
                    libusb_close(handle_);
                    handle_ = nullptr;
                }
                claimed_ = -1;
            }

        private:
            libusb_device_handle* OpenAccessory()
            {
                for (uint16_t product : AccessoryProducts)
                {
                    libusb_device_handle* handle = libusb_open_device_with_vid_pid(context_, GoogleVendor, product);
                    if (handle) return handle;
                }
                return nullptr;
            }

            bool SwitchPhone()
            {
                libusb_device** list = nullptr;
                const ssize_t count = libusb_get_device_list(context_, &list);
                if (count < 0) return false;

                bool switched = false;
                for (ssize_t i = 0; i < count && !switched; ++i)
                {
                    libusb_device_descriptor descriptor{};
                    if (libusb_get_device_descriptor(list[i], &descriptor) != 0) continue;
                    if (descriptor.bDeviceClass == LIBUSB_CLASS_HUB) continue;
                    if (descriptor.idVendor == GoogleVendor && IsAccessoryProduct(descriptor.idProduct)) continue;
                    if (vendorFilter_ >= 0 && descriptor.idVendor != vendorFilter_) continue;
                    if (productFilter_ >= 0 && descriptor.idProduct != productFilter_) continue;

                    libusb_device_handle* handle = nullptr;
                    if (libusb_open(list[i], &handle) != 0 || !handle) continue;
                    switched = StartAccessory(handle);
                    libusb_close(handle);
                }

                libusb_free_device_list(list, 1);
                return switched;
            }

            bool StartAccessory(libusb_device_handle* handle)
            {
                uint8_t version[2] = {0, 0};
                const int rc = libusb_control_transfer(handle, 0xC0, 51, 0, 0, version, 2, ControlTimeoutMs);
                if (rc != 2) return false;
                if ((version[0] | (version[1] << 8)) < 1) return false;

                for (int index = 0; index < 6; ++index)
                {
                    const char* text = AccessoryStrings[index];
                    const int sent = libusb_control_transfer(
                        handle, 0x40, 52, 0, static_cast<uint16_t>(index),
                        reinterpret_cast<unsigned char*>(const_cast<char*>(text)),
                        static_cast<uint16_t>(std::strlen(text) + 1), ControlTimeoutMs);
                    if (sent < 0) return false;
                }

                libusb_control_transfer(handle, 0x40, 53, 0, 0, nullptr, 0, ControlTimeoutMs);
                return true;
            }

            bool Claim()
            {
                libusb_device* device = libusb_get_device(handle_);
                libusb_config_descriptor* config = nullptr;
                if (libusb_get_active_config_descriptor(device, &config) != 0) return false;

                int chosen = -1;
                uint8_t in = 0;
                uint8_t out = 0;
                for (uint8_t i = 0; i < config->bNumInterfaces && chosen < 0; ++i)
                {
                    const libusb_interface& iface = config->interface[i];
                    if (iface.num_altsetting < 1) continue;
                    const libusb_interface_descriptor& alt = iface.altsetting[0];

                    uint8_t foundIn = 0;
                    uint8_t foundOut = 0;
                    for (uint8_t e = 0; e < alt.bNumEndpoints; ++e)
                    {
                        const libusb_endpoint_descriptor& endpoint = alt.endpoint[e];
                        if ((endpoint.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK) != LIBUSB_ENDPOINT_TRANSFER_TYPE_BULK) continue;
                        if (endpoint.bEndpointAddress & LIBUSB_ENDPOINT_IN)
                            foundIn = endpoint.bEndpointAddress;
                        else
                            foundOut = endpoint.bEndpointAddress;
                    }

                    if (foundIn != 0 && foundOut != 0)
                    {
                        chosen = alt.bInterfaceNumber;
                        in = foundIn;
                        out = foundOut;
                    }
                }
                libusb_free_config_descriptor(config);

                if (chosen < 0) return false;
                if (libusb_claim_interface(handle_, chosen) != 0) return false;

                claimed_ = chosen;
                endpointIn_ = in;
                endpointOut_ = out;
                return true;
            }

            bool SendHello()
            {
                int written = 0;
                uint8_t data[4];
                std::memcpy(data, Hello, sizeof(data));
                const int rc = libusb_bulk_transfer(handle_, endpointOut_, data, sizeof(data), &written, WriteTimeoutMs);
                return rc == 0;
            }

            int vendorFilter_;
            int productFilter_;
            libusb_context* context_ = nullptr;
            libusb_device_handle* handle_ = nullptr;
            int claimed_ = -1;
            uint8_t endpointIn_ = 0;
            uint8_t endpointOut_ = 0;
        };
    }

    std::unique_ptr<Transport> MakeAoaTransport(int vendorFilter, int productFilter)
    {
        return std::make_unique<AoaTransport>(vendorFilter, productFilter);
    }
}
