// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors
#include "Streamer.h"

#include <winrt/Windows.Foundation.h>

#include <algorithm>

namespace senda {
namespace {

std::wstring ComputerName() {
    wchar_t name[256]{};
    DWORD size = ARRAYSIZE(name);
    if (GetComputerNameExW(ComputerNameDnsHostname, name, &size) && name[0]) return name;
    size = ARRAYSIZE(name);
    return GetComputerNameW(name, &size) ? name : L"PC";
}

/** Cabe en la calidad elegida sin deformar la imagen, con medidas pares (lo exige H.264 en 4:2:0). */
void FitSize(uint32_t sourceWidth, uint32_t sourceHeight, const Quality& quality, uint32_t& width, uint32_t& height) {
    double scale = std::min({1.0, static_cast<double>(quality.maxWidth) / sourceWidth, static_cast<double>(quality.maxHeight) / sourceHeight});
    width = static_cast<uint32_t>(sourceWidth * scale) & ~1u;
    height = static_cast<uint32_t>(sourceHeight * scale) & ~1u;
}

}  // namespace

Streamer::~Streamer() { Stop(); }

void Streamer::Start(const StreamSettings& settings) {
    Stop();
    {
        std::lock_guard lock(mutex_);
        stopRequested_ = false;
        disconnected_ = false;
        error_.clear();
    }
    worker_ = std::thread([this, settings] { Run(settings); });
}

void Streamer::RequestStop() {
    {
        std::lock_guard lock(mutex_);
        stopRequested_ = true;
    }
    wake_.notify_all();
}

void Streamer::Stop() {
    RequestStop();
    if (worker_.joinable()) worker_.join();
}

std::wstring Streamer::Device() {
    std::lock_guard lock(mutex_);
    return device_;
}

std::wstring Streamer::EncoderName() {
    std::lock_guard lock(mutex_);
    return encoderName_;
}

std::wstring Streamer::LastError() {
    std::lock_guard lock(mutex_);
    return error_;
}

void Streamer::TakeStats(uint32_t& frames, uint64_t& bytes, int& latencyMs, uint32_t& bitrateKbps) {
    frames = encoder_.TakeEncodedFrames();
    bytes = link_.TakeSentBytes();
    latencyMs = link_.LatencyMs();
    bitrateKbps = encoder_.BitrateKbps();
}

/**
 * Una vez por segundo: si la red se atascó o el retraso sube, baja un 30 % (mínimo 2 Mbps); tras
 * 8 segundos estables sube un 15 % hasta la calidad elegida. En WiFi floja se ve algo menos nítido
 * pero sin tirones ni retraso acumulado.
 */
void Streamer::AdaptBitrate() {
    uint32_t current = encoder_.BitrateKbps();
    if (current == 0 || targetKbps_ == 0) return;
    uint32_t congestion = link_.TakeCongestionEvents();
    int latency = link_.LatencyMs();
    if (congestion > 0 || latency > 120) {
        stableSeconds_ = 0;
        encoder_.SetBitrate(std::max<uint32_t>(2000, current * 7 / 10));
    } else if (++stableSeconds_ >= 8 && current < targetKbps_) {
        stableSeconds_ = 0;
        encoder_.SetBitrate(std::min<uint32_t>(targetKbps_, current * 115 / 100));
    }
}

void Streamer::SetState(StreamState state, const std::wstring& error) {
    {
        std::lock_guard lock(mutex_);
        if (!error.empty() || state == StreamState::Streaming) error_ = error;
    }
    state_ = state;
    if (onStateChanged) onStateChanged();
}

void Streamer::Run(StreamSettings settings) {
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    std::wstring pcName = ComputerName();
    // Se fijan antes de conectar y no cambian: los hilos de la conexión los leen sin cerrojo
    link_.onKeyframeRequest = [this] { encoder_.RequestKeyframe(); };
    link_.onDisconnected = [this] {
        {
            std::lock_guard lock(mutex_);
            disconnected_ = true;
        }
        wake_.notify_all();
    };
    bool finalError = false;

    while (true) {
        {
            std::lock_guard lock(mutex_);
            if (stopRequested_) break;
            disconnected_ = false;
        }
        SetState(StreamState::Connecting);
        std::wstring device;
        ConnectResult result = link_.Connect(settings.ip, settings.port, settings.code, pcName, device);
        {
            std::lock_guard lock(mutex_);
            device_ = device;
        }
        if (result == ConnectResult::WrongCode) {
            link_.Close();
            SetState(StreamState::WrongCode, L"Código incorrecto: míralo en las propiedades de la fuente PC en el móvil.");
            finalError = true;
            break;
        }
        std::wstring retryMessage = result == ConnectResult::Unreachable ? L"No se encuentra el móvil. ¿Está Senda Studio abierto con la fuente PC?"
                                    : result == ConnectResult::NotSenda  ? L"En esa dirección no responde Senda Studio."
                                                                        : L"Se perdió la conexión con el móvil. Reintentando…";
        if (result == ConnectResult::Ok) {
            bool started = StartPipeline(settings);
            if (started) {
                SetState(StreamState::Streaming);
                std::unique_lock lock(mutex_);
                while (!wake_.wait_for(lock, std::chrono::seconds(1), [this] { return stopRequested_ || disconnected_; })) {
                    lock.unlock();
                    AdaptBitrate();
                    lock.lock();
                }
            }
            StopPipeline();
            if (!started) {
                finalError = true;
                break;
            }
        }

        {
            std::lock_guard lock(mutex_);
            if (stopRequested_) break;
        }
        SetState(StreamState::Retrying, retryMessage);
        std::unique_lock lock(mutex_);
        wake_.wait_for(lock, std::chrono::seconds(2), [this] { return stopRequested_; });
        if (stopRequested_) break;
    }
    if (!finalError) SetState(StreamState::Idle);
    winrt::uninit_apartment();
}

bool Streamer::StartPipeline(const StreamSettings& settings) {
    try {
        auto device = CreateDeviceForMonitor(settings.monitor);
        const Quality& quality = kQualities[std::min(settings.quality, std::size(kQualities) - 1)];

        MONITORINFO info{};
        info.cbSize = sizeof(info);
        GetMonitorInfoW(settings.monitor, &info);
        VideoEncoder::Config config;
        FitSize(static_cast<uint32_t>(info.rcMonitor.right - info.rcMonitor.left), static_cast<uint32_t>(info.rcMonitor.bottom - info.rcMonitor.top),
                quality, config.width, config.height);
        config.fps = quality.fps;
        config.bitrateKbps = quality.bitrateKbps;
        config.forceSoftware = settings.forceSoftware;
        targetKbps_ = quality.bitrateKbps;
        stableSeconds_ = 0;

        if (!encoder_.Start(device.get(), config, [this](const uint8_t* data, size_t size, bool keyframe, int64_t captureUs) {
                link_.SendVideoFrame(data, size, keyframe, captureUs);
            })) {
            SetState(StreamState::Failed, L"Este PC no tiene un codificador H.264 compatible.");
            return false;
        }
        {
            std::lock_guard lock(mutex_);
            encoderName_ = encoder_.Name();
        }
        link_.SendVideoFormat(config.width, config.height, config.fps);

        if (!capture_.Start(device.get(), settings.monitor, settings.cursor, config.fps,
                            [this](ID3D11Texture2D* frame, int64_t captureUs) { encoder_.SubmitFrame(frame, captureUs); })) {
            SetState(StreamState::Failed, L"No se pudo capturar la pantalla (hace falta Windows 10 versión 1903 o posterior).");
            return false;
        }
        if (settings.audio) {
            audio_.Start([this](const int16_t* pcm, size_t frames, int64_t captureUs) {
                link_.SendAudio(pcm, frames, AudioCapture::kSampleRate, AudioCapture::kChannels, captureUs);
            });
        }
        // Mientras se envía, ni el PC se suspende ni la pantalla se apaga
        SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED);
        return true;
    } catch (const winrt::hresult_error& e) {
        Log(L"Error al iniciar el envío: %s", e.message().c_str());
        SetState(StreamState::Failed, L"No se pudo preparar la captura: " + std::wstring(e.message()));
        return false;
    }
}

void Streamer::StopPipeline() {
    capture_.Stop();
    audio_.Stop();
    link_.Close();
    encoder_.Stop();
    SetThreadExecutionState(ES_CONTINUOUS);
}

}  // namespace senda
