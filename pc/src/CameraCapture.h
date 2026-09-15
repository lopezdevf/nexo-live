// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#pragma once

#include "Common.h"

#include <d3d11.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>

#include <atomic>
#include <functional>
#include <thread>

namespace sirga {

/**
 * Webcam o capturadora del PC con Media Foundation. Elige el modo de mayor resolución hasta 1080p a
 * 24-30 fps, deja que Windows lo convierta a BGRA (MJPEG, YUY2, NV12…) y entrega cada fotograma en
 * una textura de [device], lista para el mismo codificador que la pantalla.
 */
class CameraCapture {
public:
    using FrameCallback = std::function<void(ID3D11Texture2D* frame, int64_t captureUs)>;

    ~CameraCapture();

    /** Abre la cámara y elige el modo. Después se sabe el tamaño y los fps para configurar el codificador. */
    bool Open(ID3D11Device* device, const std::wstring& symbolicLink);
    /** Empieza a entregar fotogramas. */
    void Begin(FrameCallback onFrame);
    void Stop();

    uint32_t Width() const { return width_; }
    uint32_t Height() const { return height_; }
    uint32_t Fps() const { return fps_; }

private:
    void Loop();
    bool Upload(IMFSample* sample);

    winrt::com_ptr<ID3D11Device> device_;
    winrt::com_ptr<ID3D11DeviceContext> context_;
    winrt::com_ptr<ID3D11Texture2D> texture_;
    winrt::com_ptr<IMFMediaSource> source_;
    winrt::com_ptr<IMFSourceReader> reader_;
    FrameCallback onFrame_;
    uint32_t width_ = 0;
    uint32_t height_ = 0;
    uint32_t fps_ = 30;
    LONG stride_ = 0;
    std::atomic<bool> running_{false};
    std::thread thread_;
};

}  // namespace sirga
