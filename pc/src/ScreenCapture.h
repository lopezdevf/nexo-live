// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors
#pragma once

#include "Common.h"

#include <d3d11.h>

#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <functional>
#include <mutex>
#include <vector>

namespace nexo {

struct MonitorInfo {
    HMONITOR handle = nullptr;
    std::wstring name;
    RECT rect{};
    bool primary = false;
};

std::vector<MonitorInfo> EnumerateMonitors();

/** Crea el dispositivo D3D11 en la gráfica que muestra [monitor]: capturar y codificar en la misma evita copias entre GPUs. */
winrt::com_ptr<ID3D11Device> CreateDeviceForMonitor(HMONITOR monitor);

/**
 * Captura un monitor con Windows.Graphics.Capture: la imagen llega como textura de la GPU en
 * cuanto el escritorio compone un fotograma, incluidos los juegos a pantalla completa.
 */
class ScreenCapture {
public:
    using FrameCallback = std::function<void(ID3D11Texture2D* frame, int64_t captureUs)>;

    bool Start(ID3D11Device* device, HMONITOR monitor, bool cursor, uint32_t maxFps, FrameCallback onFrame);
    void Stop();

private:
    void OnFrameArrived(winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool const& pool, winrt::Windows::Foundation::IInspectable const&);

    winrt::com_ptr<ID3D11Device> device_;
    winrt::Windows::Graphics::DirectX::Direct3D11::IDirect3DDevice winrtDevice_{nullptr};
    winrt::Windows::Graphics::Capture::GraphicsCaptureItem item_{nullptr};
    winrt::Windows::Graphics::Capture::Direct3D11CaptureFramePool pool_{nullptr};
    winrt::Windows::Graphics::Capture::GraphicsCaptureSession session_{nullptr};
    winrt::event_token frameToken_{};
    winrt::Windows::Graphics::SizeInt32 size_{};
    FrameCallback onFrame_;
    int64_t minIntervalUs_ = 0;
    int64_t lastFrameUs_ = 0;
    std::mutex mutex_;
};

}  // namespace nexo
