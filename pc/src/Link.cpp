// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "Link.h"

#include <iphlpapi.h>
#include <ws2tcpip.h>

#include <algorithm>
#include <chrono>
#include <set>

namespace sirga {
namespace {

constexpr uint16_t kDiscoveryPort = 9750;
constexpr uint8_t kVideoFormat = 0x01;
constexpr uint8_t kVideoFrame = 0x02;
constexpr uint8_t kAudioPcm = 0x03;
constexpr uint8_t kTimeReply = 0x05;
constexpr uint8_t kLatencyReport = 0x07;
constexpr uint8_t kKeyframeRequest = 0x81;
constexpr uint8_t kTimeRequest = 0x82;
constexpr uint8_t kFrameShown = 0x83;
constexpr size_t kMaxPacket = 16 * 1024 * 1024;
/** Más fotogramas en cola que esto significa que la red va por detrás: mejor saltar al presente. */
constexpr size_t kMaxQueuedVideoFrames = 3;

void PutU16(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value >> 8));
    out.push_back(static_cast<uint8_t>(value));
}

void PutU32(std::vector<uint8_t>& out, uint32_t value) {
    for (int shift = 24; shift >= 0; shift -= 8) out.push_back(static_cast<uint8_t>(value >> shift));
}

void PutU64(std::vector<uint8_t>& out, uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) out.push_back(static_cast<uint8_t>(value >> shift));
}

uint16_t GetU16(const uint8_t* p) { return static_cast<uint16_t>((p[0] << 8) | p[1]); }

std::vector<uint8_t> Header(uint8_t type, size_t payloadSize) {
    std::vector<uint8_t> packet;
    packet.reserve(5 + payloadSize);
    packet.push_back(type);
    PutU32(packet, static_cast<uint32_t>(payloadSize));
    return packet;
}

/** Direcciones de difusión de cada adaptador IPv4 activo (además de 255.255.255.255). */
std::vector<in_addr> BroadcastAddresses() {
    std::vector<in_addr> result;
    ULONG size = 16 * 1024;
    std::vector<uint8_t> buffer(size);
    auto addresses = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, addresses, &size) ==
        ERROR_BUFFER_OVERFLOW) {
        buffer.resize(size);
        addresses = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
        if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr, addresses, &size) != NO_ERROR) {
            return result;
        }
    }
    for (auto adapter = addresses; adapter; adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp || adapter->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        for (auto unicast = adapter->FirstUnicastAddress; unicast; unicast = unicast->Next) {
            auto ipv4 = reinterpret_cast<sockaddr_in*>(unicast->Address.lpSockaddr);
            if (ipv4->sin_family != AF_INET) continue;
            ULONG prefix = unicast->OnLinkPrefixLength;
            if (prefix == 0 || prefix >= 32) continue;
            uint32_t host = ntohl(ipv4->sin_addr.s_addr);
            uint32_t mask = prefix == 0 ? 0 : 0xFFFFFFFFu << (32 - prefix);
            in_addr broadcast{};
            broadcast.s_addr = htonl(host | ~mask);
            result.push_back(broadcast);
        }
    }
    return result;
}

}  // namespace

std::vector<PhoneInfo> DiscoverPhones(int timeoutMs) {
    std::vector<PhoneInfo> phones;
    SOCKET s = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s == INVALID_SOCKET) return phones;
    BOOL on = TRUE;
    setsockopt(s, SOL_SOCKET, SO_BROADCAST, reinterpret_cast<const char*>(&on), sizeof(on));
    sockaddr_in local{};
    local.sin_family = AF_INET;
    bind(s, reinterpret_cast<sockaddr*>(&local), sizeof(local));

    static const char query[] = {'S', 'G', 'L', '1', '?'};
    std::vector<in_addr> targets = BroadcastAddresses();
    in_addr everyone{};
    everyone.s_addr = INADDR_BROADCAST;
    targets.push_back(everyone);
    for (const auto& target : targets) {
        sockaddr_in to{};
        to.sin_family = AF_INET;
        to.sin_port = htons(kDiscoveryPort);
        to.sin_addr = target;
        sendto(s, query, sizeof(query), 0, reinterpret_cast<sockaddr*>(&to), sizeof(to));
    }

    std::set<std::pair<std::string, uint16_t>> seen;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    char buffer[1024];
    while (true) {
        auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - std::chrono::steady_clock::now()).count();
        if (left <= 0) break;
        fd_set read;
        FD_ZERO(&read);
        FD_SET(s, &read);
        timeval tv{static_cast<long>(left / 1000), static_cast<long>((left % 1000) * 1000)};
        if (select(0, &read, nullptr, nullptr, &tv) <= 0) break;
        sockaddr_in from{};
        int fromLen = sizeof(from);
        int n = recvfrom(s, buffer, sizeof(buffer), 0, reinterpret_cast<sockaddr*>(&from), &fromLen);
        if (n < 5 + 2 + 2 || memcmp(buffer, "SGL1!", 5) != 0) continue;
        auto p = reinterpret_cast<const uint8_t*>(buffer);
        PhoneInfo phone;
        phone.port = GetU16(p + 5);
        size_t deviceLen = GetU16(p + 7);
        if (9 + deviceLen + 2 > static_cast<size_t>(n)) continue;
        phone.device = FromUtf8(std::string(buffer + 9, deviceLen));
        size_t sourceLen = GetU16(p + 9 + deviceLen);
        if (11 + deviceLen + sourceLen > static_cast<size_t>(n)) continue;
        phone.source = FromUtf8(std::string(buffer + 11 + deviceLen, sourceLen));
        char ip[INET_ADDRSTRLEN]{};
        inet_ntop(AF_INET, &from.sin_addr, ip, sizeof(ip));
        phone.ip = ip;
        if (seen.insert({phone.ip, phone.port}).second) phones.push_back(std::move(phone));
    }
    closesocket(s);
    return phones;
}

LinkSession::LinkSession() = default;

LinkSession::~LinkSession() { Close(); }

ConnectResult LinkSession::Connect(const std::string& ip, uint16_t port, uint16_t code, const std::wstring& pcName, std::wstring& deviceName) {
    Close();
    closing_ = false;

    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* result = nullptr;
    if (getaddrinfo(ip.c_str(), std::to_string(port).c_str(), &hints, &result) != 0 || !result) return ConnectResult::Unreachable;

    SOCKET s = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        freeaddrinfo(result);
        return ConnectResult::Unreachable;
    }
    // Conexión con límite de tiempo: sin él, una IP equivocada bloquea 20 segundos
    u_long nonBlocking = 1;
    ioctlsocket(s, FIONBIO, &nonBlocking);
    ::connect(s, result->ai_addr, static_cast<int>(result->ai_addrlen));
    freeaddrinfo(result);
    fd_set write;
    FD_ZERO(&write);
    FD_SET(s, &write);
    fd_set error = write;
    timeval tv{3, 0};
    if (select(0, nullptr, &write, &error, &tv) <= 0 || FD_ISSET(s, &error)) {
        closesocket(s);
        return ConnectResult::Unreachable;
    }
    nonBlocking = 0;
    ioctlsocket(s, FIONBIO, &nonBlocking);

    BOOL on = TRUE;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<const char*>(&on), sizeof(on));
    // Búfer pequeño: con la red saturada la cola detecta el atasco en decenas de milisegundos y salta
    // fotogramas, en lugar de acumular medio segundo de vídeo dentro del sistema
    int sendBuffer = 256 * 1024;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<const char*>(&sendBuffer), sizeof(sendBuffer));
    DWORD timeout = 5000;
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof(timeout));
    socket_ = s;

    std::string name = ToUtf8(pcName).substr(0, 256);
    std::vector<uint8_t> hello = {'S', 'G', 'L', '1'};
    PutU16(hello, code);
    PutU16(hello, static_cast<uint32_t>(name.size()));
    hello.insert(hello.end(), name.begin(), name.end());
    uint8_t reply[7];
    if (!SendAll(hello.data(), hello.size()) || !RecvAll(reply, sizeof(reply))) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        return ConnectResult::NotSirga;
    }
    if (memcmp(reply, "SGL1", 4) != 0) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        return ConnectResult::NotSirga;
    }
    std::string device(GetU16(reply + 5), '\0');
    if (!device.empty() && !RecvAll(reinterpret_cast<uint8_t*>(device.data()), device.size())) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        return ConnectResult::NotSirga;
    }
    deviceName = FromUtf8(device);
    if (reply[4] != 0) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
        return ConnectResult::WrongCode;
    }

    latencyMs_ = -1;
    windowSumUs_ = 0;
    windowSamples_ = 0;
    windowStartUs_ = 0;
    connected_ = true;
    writer_ = std::thread([this] { WriterLoop(); });
    reader_ = std::thread([this] { ReaderLoop(); });
    return ConnectResult::Ok;
}

void LinkSession::Close() {
    closing_ = true;
    connected_ = false;
    if (socket_ != INVALID_SOCKET) shutdown(socket_, SD_BOTH);
    queueSignal_.notify_all();
    if (writer_.joinable() && writer_.get_id() != std::this_thread::get_id()) writer_.join();
    if (reader_.joinable() && reader_.get_id() != std::this_thread::get_id()) reader_.join();
    if (socket_ != INVALID_SOCKET) {
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
    }
    std::lock_guard lock(queueMutex_);
    queue_.clear();
    queuedVideoFrames_ = 0;
    dropUntilKeyframe_ = false;
}

void LinkSession::SendVideoFormat(uint32_t width, uint32_t height, uint32_t fps) {
    Packet packet;
    packet.data = Header(kVideoFormat, 5);
    PutU16(packet.data, width);
    PutU16(packet.data, height);
    packet.data.push_back(static_cast<uint8_t>(fps));
    Enqueue(std::move(packet));
}

void LinkSession::SendVideoFrame(const uint8_t* data, size_t size, bool keyframe, int64_t captureUs) {
    if (!connected_ || size + 9 > kMaxPacket) return;
    Packet packet;
    packet.video = true;
    packet.keyframe = keyframe;
    packet.data = Header(kVideoFrame, 9 + size);
    packet.data.push_back(keyframe ? 1 : 0);
    PutU64(packet.data, static_cast<uint64_t>(captureUs));
    packet.data.insert(packet.data.end(), data, data + size);
    Enqueue(std::move(packet));
}

void LinkSession::SendAudio(const int16_t* pcm, size_t frames, uint32_t sampleRate, uint32_t channels, int64_t captureUs) {
    if (!connected_) return;
    size_t bytes = frames * channels * sizeof(int16_t);
    Packet packet;
    packet.data = Header(kAudioPcm, 13 + bytes);
    PutU32(packet.data, sampleRate);
    packet.data.push_back(static_cast<uint8_t>(channels));
    PutU64(packet.data, static_cast<uint64_t>(captureUs));
    auto raw = reinterpret_cast<const uint8_t*>(pcm);
    packet.data.insert(packet.data.end(), raw, raw + bytes);  // PCM little-endian, como en x86/ARM
    Enqueue(std::move(packet));
}

void LinkSession::Enqueue(Packet&& packet) {
    bool wantKeyframe = false;
    {
        std::lock_guard lock(queueMutex_);
        if (!connected_) return;
        if (packet.video) {
            if (dropUntilKeyframe_ && !packet.keyframe) return;
            if (queuedVideoFrames_ >= kMaxQueuedVideoFrames) {
                // La red va por detrás: se tira el vídeo pendiente y se salta al presente
                std::erase_if(queue_, [](const Packet& p) { return p.video; });
                queuedVideoFrames_ = 0;
                congestionEvents_++;
                if (!packet.keyframe) {
                    dropUntilKeyframe_ = true;
                    wantKeyframe = true;
                }
            }
            if (!wantKeyframe) {
                dropUntilKeyframe_ = false;
                queuedVideoFrames_++;
                queue_.push_back(std::move(packet));
            }
        } else {
            queue_.push_back(std::move(packet));
        }
    }
    queueSignal_.notify_one();
    if (wantKeyframe && onKeyframeRequest) onKeyframeRequest();
}

void LinkSession::WriterLoop() {
    while (true) {
        Packet packet;
        {
            std::unique_lock lock(queueMutex_);
            queueSignal_.wait(lock, [this] { return closing_ || !queue_.empty(); });
            if (closing_) return;
            packet = std::move(queue_.front());
            queue_.pop_front();
            if (packet.video) queuedVideoFrames_--;
        }
        if (!SendAll(packet.data.data(), packet.data.size())) {
            Fail();
            return;
        }
    }
}

void LinkSession::ReaderLoop() {
    std::vector<uint8_t> payload;
    while (!closing_) {
        uint8_t header[5];
        if (!RecvAll(header, sizeof(header))) {
            Fail();
            return;
        }
        uint32_t length = (static_cast<uint32_t>(header[1]) << 24) | (header[2] << 16) | (header[3] << 8) | header[4];
        if (length > kMaxPacket) {
            Fail();
            return;
        }
        payload.resize(length);
        if (length > 0 && !RecvAll(payload.data(), length)) {
            Fail();
            return;
        }
        if (header[0] == kTimeRequest && length >= 8) {
            // Se responde al momento, fuera de la cola, para que la medida del retraso sea exacta
            std::vector<uint8_t> reply = Header(kTimeReply, 16);
            reply.insert(reply.end(), payload.begin(), payload.begin() + 8);
            PutU64(reply, static_cast<uint64_t>(NowUs()));
            if (!SendAll(reply.data(), reply.size())) {
                Fail();
                return;
            }
        } else if (header[0] == kKeyframeRequest) {
            if (onKeyframeRequest) onKeyframeRequest();
        } else if (header[0] == kFrameShown && length >= 8) {
            // Captura → decodificado en el móvil → aviso de vuelta. Es una cota superior del retraso real (incluye
            // la vuelta del aviso) y no depende de sincronizar relojes. Se informa la media de cada segundo
            uint64_t captureUs = 0;
            for (int i = 0; i < 8; i++) captureUs = (captureUs << 8) | payload[i];
            int64_t now = NowUs();
            int64_t sample = now - static_cast<int64_t>(captureUs);
            if (sample >= 0 && sample < 5'000'000) {
                windowSumUs_ += sample;
                windowSamples_++;
            }
            if (windowStartUs_ == 0) windowStartUs_ = now;
            if (now - windowStartUs_ >= 1'000'000) {
                if (windowSamples_ > 0) {
                    int ms = static_cast<int>((windowSumUs_ / windowSamples_ + 500) / 1000);
                    latencyMs_ = ms;
                    std::vector<uint8_t> report = Header(kLatencyReport, 4);
                    PutU32(report, static_cast<uint32_t>(ms));
                    if (!SendAll(report.data(), report.size())) {
                        Fail();
                        return;
                    }
                }
                windowSumUs_ = 0;
                windowSamples_ = 0;
                windowStartUs_ = now;
            }
        }
    }
}

bool LinkSession::SendAll(const uint8_t* data, size_t size) {
    std::lock_guard lock(sendMutex_);
    while (size > 0) {
        int n = send(socket_, reinterpret_cast<const char*>(data), static_cast<int>(std::min<size_t>(size, 1 << 20)), 0);
        if (n <= 0) return false;
        data += n;
        size -= n;
        sentBytes_ += n;
    }
    return true;
}

bool LinkSession::RecvAll(uint8_t* data, size_t size) {
    while (size > 0) {
        int n = recv(socket_, reinterpret_cast<char*>(data), static_cast<int>(size), 0);
        if (n <= 0) return false;
        data += n;
        size -= n;
    }
    return true;
}

void LinkSession::Fail() {
    bool wasConnected = connected_.exchange(false);
    closing_ = true;
    queueSignal_.notify_all();
    if (wasConnected && onDisconnected) onDisconnected();
}

}  // namespace sirga
