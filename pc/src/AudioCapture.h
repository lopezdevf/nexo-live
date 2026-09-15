// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#pragma once

#include "Common.h"

#include <atomic>
#include <functional>
#include <thread>

namespace sirga {

/**
 * Captura lo que suena en el PC (WASAPI en modo loopback del altavoz predeterminado) o un micrófono
 * concreto, ya convertido por Windows a 48 kHz, 16 bits y estéreo. Con el sonido del PC, si cambia la
 * salida de audio (p. ej. se conectan unos auriculares), sigue a la nueva.
 */
class AudioCapture {
public:
    static constexpr uint32_t kSampleRate = 48000;
    static constexpr uint32_t kChannels = 2;
    using Callback = std::function<void(const int16_t* pcm, size_t frames, int64_t captureUs)>;

    ~AudioCapture();
    /** Sin [microphoneId], lo que suena en el PC; con él, ese micrófono (id de WASAPI). */
    void Start(Callback onAudio, const std::wstring& microphoneId = {});
    void Stop();

private:
    void Loop();

    Callback onAudio_;
    std::wstring microphoneId_;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

}  // namespace sirga
