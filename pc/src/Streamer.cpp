// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#include "Streamer.h"

#include <winrt/Windows.Foundation.h>

#include <algorithm>

namespace sirga {
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

void Streamer::TakeStats(uint32_t& frames, uint64_t& bytes, int& latencyMs, uint32_t& bitrateKbps, uint32_t& keyframes, uint64_t& encodedBytes) {
    frames = encoder_.TakeEncodedFrames();
    keyframes = encoder_.TakeKeyframes();
    encodedBytes = encoder_.TakeEncodedBytes();
    bytes = link_.TakeSentBytes();
    latencyMs = link_.LatencyMs();
    bitrateKbps = encoder_.BitrateKbps();
}

/**
 * Una vez por segundo: si la red se atascó o el retraso sube, baja el bitrate (mínimo 2 Mbps); tras unos
 * segundos estables lo sube hasta la calidad elegida. En WiFi floja se ve algo menos nítido pero sin tirones
 * ni retraso acumulado. Si el codificador tiene que reiniciarse para cada cambio, los pasos son más grandes y
 * como mucho uno cada 4 segundos.
 */
void Streamer::AdaptBitrate() {
    uint32_t current = encoder_.BitrateKbps();
    if (current == 0 || targetKbps_ == 0) return;
    uint32_t congestion = link_.TakeCongestionEvents();
    int latency = link_.LatencyMs();
    bool live = encoder_.SupportsLiveBitrate();
    if (secondsSinceChange_ < 3600) secondsSinceChange_++;
    if (congestion > 0 || latency > 120) {
        stableSeconds_ = 0;
        if (!live && secondsSinceChange_ < 4) return;
        uint32_t next = std::max<uint32_t>(2000, current * (live ? 7 : 6) / 10);
        // Reiniciar el codificador para acercarse unos pocos kbps al mínimo no compensa: se va directo al mínimo
        if (!live && next < 2500) next = 2000;
        if (next == current) return;
        encoder_.SetBitrate(next);
        secondsSinceChange_ = 0;
    } else if (++stableSeconds_ >= (live ? 8 : 10) && current < targetKbps_) {
        stableSeconds_ = 0;
        encoder_.SetBitrate(std::min<uint32_t>(targetKbps_, current * (live ? 115 : 130) / 100));
        secondsSinceChange_ = 0;
    }
}

/** Cada 3 s: si se conecta o desconecta una cámara o un micrófono, el móvil recibe la lista nueva. */
void Streamer::RefreshDeviceList() {
    if (++deviceListSeconds_ < 3) return;
    deviceListSeconds_ = 0;
    auto now = EnumerateDevices();
    if (now == devices_) return;
    devices_ = std::move(now);
    Log(L"Dispositivos del PC: %zu", devices_.size());
    link_.SendDeviceList(devices_);
}

/** Aplica la lista de cámaras y micrófonos que quiere el móvil: para las que sobran y abre las nuevas. */
void Streamer::SyncDevices() {
    std::vector<Subscription> wanted;
    {
        std::lock_guard lock(mutex_);
        wanted = wanted_;
    }
    auto isWanted = [&](const DeviceStream& s) {
        return std::any_of(wanted.begin(), wanted.end(), [&](const Subscription& w) { return w.stream == s.stream && w.deviceId == s.device.id; });
    };
    // Parar fuera del cerrojo: el codificador puede estar entregando y pedir un fotograma clave por la misma vía
    std::vector<std::shared_ptr<DeviceStream>> removed;
    {
        std::lock_guard lock(devicesMutex_);
        for (auto it = deviceStreams_.begin(); it != deviceStreams_.end();) {
            if (isWanted(**it)) {
                ++it;
            } else {
                removed.push_back(*it);
                it = deviceStreams_.erase(it);
            }
        }
    }
    for (auto& s : removed) StopDeviceStream(*s);

    for (const auto& w : wanted) {
        {
            std::lock_guard lock(devicesMutex_);
            if (std::any_of(deviceStreams_.begin(), deviceStreams_.end(), [&](const auto& s) { return s->stream == w.stream; })) continue;
        }
        auto device = std::find_if(devices_.begin(), devices_.end(), [&](const PcDevice& d) { return d.id == w.deviceId; });
        if (device == devices_.end()) {
            Log(L"El móvil pide un dispositivo que ya no está conectado");
            continue;
        }
        auto stream = std::make_shared<DeviceStream>();
        stream->stream = w.stream;
        stream->device = *device;
        if (!StartDeviceStream(*stream)) {
            StopDeviceStream(*stream);
            continue;
        }
        std::lock_guard lock(devicesMutex_);
        deviceStreams_.push_back(std::move(stream));
    }
}

bool Streamer::StartDeviceStream(DeviceStream& s) {
    const uint8_t id = s.stream;
    if (s.device.kind == DeviceKind::Microphone) {
        s.microphone = std::make_unique<AudioCapture>();
        s.microphone->Start(
            [this, id](const int16_t* pcm, size_t frames, int64_t captureUs) {
                link_.SendStreamAudio(id, pcm, frames, AudioCapture::kSampleRate, AudioCapture::kChannels, captureUs);
            },
            s.device.id);
        Log(L"Enviando el micrófono «%s»", s.device.name.c_str());
        return true;
    }
    if (!d3dDevice_) return false;
    s.camera = std::make_unique<CameraCapture>();
    if (!s.camera->Open(d3dDevice_.get(), s.device.id)) return false;
    VideoEncoder::Config config;
    config.width = s.camera->Width() & ~1u;
    config.height = s.camera->Height() & ~1u;
    config.fps = std::min<uint32_t>(s.camera->Fps(), 30);
    config.bitrateKbps = config.height >= 1080 ? 6000 : config.height >= 720 ? 4000 : 2500;
    s.encoder = std::make_unique<VideoEncoder>();
    if (!s.encoder->Start(d3dDevice_.get(), config, [this, id](const uint8_t* data, size_t size, bool keyframe, int64_t captureUs) {
            link_.SendStreamVideoFrame(id, data, size, keyframe, captureUs);
        })) {
        return false;
    }
    link_.SendStreamVideoFormat(id, config.width, config.height, config.fps);
    VideoEncoder* encoder = s.encoder.get();
    s.camera->Begin([encoder](ID3D11Texture2D* frame, int64_t captureUs) { encoder->SubmitFrame(frame, captureUs); });
    Log(L"Enviando la cámara «%s»", s.device.name.c_str());
    return true;
}

void Streamer::StopDeviceStream(DeviceStream& s) {
    if (s.camera) s.camera->Stop();
    if (s.encoder) s.encoder->Stop();
    if (s.microphone) s.microphone->Stop();
}

void Streamer::StopDeviceStreams() {
    std::vector<std::shared_ptr<DeviceStream>> all;
    {
        std::lock_guard lock(devicesMutex_);
        all.swap(deviceStreams_);
    }
    for (auto& s : all) StopDeviceStream(*s);
}

void Streamer::KeyframeForStream(uint8_t stream) {
    std::shared_ptr<DeviceStream> target;
    {
        std::lock_guard lock(devicesMutex_);
        for (auto& s : deviceStreams_)
            if (s->stream == stream) target = s;
    }
    if (target && target->encoder) target->encoder->RequestKeyframe();
}

std::vector<std::wstring> Streamer::ActiveDevices() {
    std::vector<std::wstring> names;
    std::lock_guard lock(devicesMutex_);
    for (auto& s : deviceStreams_) names.push_back(s->device.name);
    return names;
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
    link_.onKeyframeRequest = [this](uint8_t stream) {
        if (stream == 0) {
            encoder_.RequestKeyframe();
        } else {
            KeyframeForStream(stream);
        }
    };
    link_.onSubscribe = [this](std::vector<Subscription> wanted) {
        {
            std::lock_guard lock(mutex_);
            wanted_ = std::move(wanted);
            devicesPending_ = true;
        }
        wake_.notify_all();
    };
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
        {
            // Antes de conectar: el móvil pide sus cámaras y micrófonos nada más saludar, mientras aquí aún se prepara la captura
            std::lock_guard lock(mutex_);
            wanted_.clear();
            devicesPending_ = false;
        }
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
        std::wstring retryMessage = result == ConnectResult::Unreachable ? L"No se encuentra el móvil. ¿Está Sirga Studio abierto con la fuente PC?"
                                    : result == ConnectResult::NotSirga  ? L"En esa dirección no responde Sirga Studio."
                                                                        : L"Se perdió la conexión con el móvil. Reintentando…";
        if (result == ConnectResult::Ok) {
            bool started = StartPipeline(settings);
            if (started) {
                SetState(StreamState::Streaming);
                std::unique_lock lock(mutex_);
                while (true) {
                    bool woke = wake_.wait_for(lock, std::chrono::seconds(1), [this] { return stopRequested_ || disconnected_ || devicesPending_; });
                    if (stopRequested_ || disconnected_) break;
                    bool pending = devicesPending_;
                    devicesPending_ = false;
                    lock.unlock();
                    if (pending) SyncDevices();
                    if (!woke) {
                        AdaptBitrate();
                        RefreshDeviceList();
                    }
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
        secondsSinceChange_ = 0;

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
        d3dDevice_ = device;
        devices_ = EnumerateDevices();
        deviceListSeconds_ = 0;
        link_.SendDeviceList(devices_);

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
    StopDeviceStreams();
    link_.Close();
    encoder_.Stop();
    d3dDevice_ = nullptr;
    SetThreadExecutionState(ES_CONTINUOUS);
}

}  // namespace sirga
