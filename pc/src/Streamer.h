// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors
#pragma once

#include "AudioCapture.h"
#include "Link.h"
#include "ScreenCapture.h"
#include "VideoEncoder.h"

#include <condition_variable>
#include <functional>
#include <mutex>
#include <thread>

namespace nexo {

struct Quality {
    const wchar_t* label;
    uint32_t maxWidth;
    uint32_t maxHeight;
    uint32_t fps;
    uint32_t bitrateKbps;
};

/** Calidades que ofrece la app. En red local sobra ancho de banda: se prima la nitidez y la fluidez. */
inline constexpr Quality kQualities[] = {
    {L"Máxima · 1080p a 60 fps", 1920, 1080, 60, 16000},
    {L"Alta · 1080p a 30 fps", 1920, 1080, 30, 10000},
    {L"Fluida · 720p a 60 fps", 1280, 720, 60, 8000},
    {L"Ligera · 720p a 30 fps (WiFi lenta)", 1280, 720, 30, 4000},
};

struct StreamSettings {
    std::string ip;
    uint16_t port = 9000;
    uint16_t code = 0;
    HMONITOR monitor = nullptr;
    size_t quality = 0;
    bool audio = true;
    bool cursor = true;
};

enum class StreamState { Idle, Connecting, Streaming, WrongCode, Retrying, Failed };

/**
 * Une captura, codificador, audio y conexión. Si el móvil se desconecta (se sale de la app, se
 * corta la WiFi…), vuelve a intentarlo cada dos segundos hasta que el usuario pulse Desconectar.
 */
class Streamer {
public:
    ~Streamer();

    void Start(const StreamSettings& settings);
    /** Pide parar sin esperar (para no bloquear la ventana); el estado pasa a Idle al terminar. */
    void RequestStop();
    void Stop();

    StreamState State() const { return state_; }
    std::wstring Device();
    std::wstring EncoderName();
    std::wstring LastError();
    /** fps codificados y kbps enviados desde la última llamada. */
    void TakeStats(uint32_t& frames, uint64_t& bytes, int& latencyMs);

    /** Se llama desde otros hilos cada vez que cambia el estado. */
    std::function<void()> onStateChanged;

private:
    void Run(StreamSettings settings);
    bool StartPipeline(const StreamSettings& settings);
    void StopPipeline();
    void SetState(StreamState state, const std::wstring& error = {});

    std::thread worker_;
    std::mutex mutex_;
    std::condition_variable wake_;
    bool stopRequested_ = false;
    bool disconnected_ = false;
    std::atomic<StreamState> state_{StreamState::Idle};
    std::wstring device_;
    std::wstring encoderName_;
    std::wstring error_;

    LinkSession link_;
    ScreenCapture capture_;
    VideoEncoder encoder_;
    AudioCapture audio_;
};

}  // namespace nexo
