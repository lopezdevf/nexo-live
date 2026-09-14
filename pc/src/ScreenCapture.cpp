// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Senda Studio contributors
#include "ScreenCapture.h"

#include <dxgi1_6.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Metadata.h>

#include <algorithm>

namespace senda {

using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;

std::vector<MonitorInfo> EnumerateMonitors() {
    std::vector<MonitorInfo> monitors;
    EnumDisplayMonitors(nullptr, nullptr, [](HMONITOR handle, HDC, LPRECT, LPARAM data) -> BOOL {
        MONITORINFOEXW info{};
        info.cbSize = sizeof(info);
        if (GetMonitorInfoW(handle, &info)) {
            MonitorInfo monitor;
            monitor.handle = handle;
            monitor.rect = info.rcMonitor;
            monitor.primary = (info.dwFlags & MONITORINFOF_PRIMARY) != 0;
            // Nombre del modelo si Windows lo conoce; si no, el del dispositivo
            DISPLAY_DEVICEW device{};
            device.cb = sizeof(device);
            monitor.name = EnumDisplayDevicesW(info.szDevice, 0, &device, 0) && device.DeviceString[0] ? device.DeviceString : info.szDevice;
            reinterpret_cast<std::vector<MonitorInfo>*>(data)->push_back(monitor);
        }
        return TRUE;
    }, reinterpret_cast<LPARAM>(&monitors));
    std::stable_partition(monitors.begin(), monitors.end(), [](const MonitorInfo& m) { return m.primary; });
    return monitors;
}

winrt::com_ptr<ID3D11Device> CreateDeviceForMonitor(HMONITOR monitor) {
    winrt::com_ptr<IDXGIFactory1> factory;
    winrt::check_hresult(CreateDXGIFactory1(winrt::guid_of<IDXGIFactory1>(), factory.put_void()));
    winrt::com_ptr<IDXGIAdapter1> chosen;
    winrt::com_ptr<IDXGIAdapter1> adapter;
    for (UINT i = 0; !chosen && factory->EnumAdapters1(i, adapter.put()) != DXGI_ERROR_NOT_FOUND; i++) {
        winrt::com_ptr<IDXGIOutput> output;
        for (UINT j = 0; adapter->EnumOutputs(j, output.put()) != DXGI_ERROR_NOT_FOUND; j++) {
            DXGI_OUTPUT_DESC desc{};
            if (SUCCEEDED(output->GetDesc(&desc)) && desc.Monitor == monitor) {
                chosen = adapter;
                break;
            }
            output = nullptr;
        }
        adapter = nullptr;
    }

    UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
    static const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1};
    winrt::com_ptr<ID3D11Device> device;
    HRESULT hr = D3D11CreateDevice(chosen.get(), chosen ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, levels,
                                   ARRAYSIZE(levels), D3D11_SDK_VERSION, device.put(), nullptr, nullptr);
    if (FAILED(hr)) {
        // Sin soporte de vídeo en el controlador: se codificará por software
        hr = D3D11CreateDevice(chosen.get(), chosen ? D3D_DRIVER_TYPE_UNKNOWN : D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                               levels, ARRAYSIZE(levels), D3D11_SDK_VERSION, device.put(), nullptr, nullptr);
    }
    winrt::check_hresult(hr);
    // Captura, conversión de color y codificador usan el dispositivo desde hilos distintos
    if (auto multithread = device.try_as<ID3D10Multithread>()) multithread->SetMultithreadProtected(TRUE);
    return device;
}

bool ScreenCapture::Start(ID3D11Device* device, HMONITOR monitor, bool cursor, uint32_t maxFps, FrameCallback onFrame) {
    try {
        if (!GraphicsCaptureSession::IsSupported()) {
            Log(L"Windows.Graphics.Capture no está disponible");
            return false;
        }
        device_.copy_from(device);
        onFrame_ = std::move(onFrame);
        minIntervalUs_ = maxFps > 0 ? 1'000'000 / maxFps - 2'000 : 0;
        lastFrameUs_ = 0;

        auto dxgiDevice = device_.as<IDXGIDevice>();
        winrt::com_ptr<::IInspectable> inspectable;
        winrt::check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), inspectable.put()));
        winrtDevice_ = inspectable.as<Direct3D11::IDirect3DDevice>();

        auto interop = winrt::get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt::check_hresult(interop->CreateForMonitor(monitor, winrt::guid_of<GraphicsCaptureItem>(), winrt::put_abi(item_)));
        size_ = item_.Size();

        pool_ = Direct3D11CaptureFramePool::CreateFreeThreaded(winrtDevice_, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size_);
        session_ = pool_.CreateCaptureSession(item_);
        try {
            session_.IsCursorCaptureEnabled(cursor);
        } catch (...) {
        }
        try {
            // Windows 11: sin el borde amarillo alrededor de la pantalla
            session_.IsBorderRequired(false);
        } catch (...) {
        }
        try {
            if (maxFps > 0) session_.MinUpdateInterval(winrt::Windows::Foundation::TimeSpan(10'000'000 / maxFps));
        } catch (...) {
        }
        frameToken_ = pool_.FrameArrived({this, &ScreenCapture::OnFrameArrived});
        session_.StartCapture();
        Log(L"Captura iniciada: %dx%d", size_.Width, size_.Height);
        return true;
    } catch (const winrt::hresult_error& e) {
        Log(L"No se pudo iniciar la captura: %s (0x%08X)", e.message().c_str(), static_cast<uint32_t>(e.code()));
        Stop();
        return false;
    }
}

void ScreenCapture::Stop() {
    Direct3D11CaptureFramePool pool{nullptr};
    GraphicsCaptureSession session{nullptr};
    {
        // Se suelta el cerrojo antes de cerrar: un fotograma en curso podría estar esperándolo
        std::lock_guard lock(mutex_);
        pool = pool_;
        session = session_;
        pool_ = nullptr;
        session_ = nullptr;
        onFrame_ = nullptr;
    }
    try {
        if (pool) {
            pool.FrameArrived(frameToken_);
            pool.Close();
        }
        if (session) session.Close();
    } catch (...) {
    }
    item_ = nullptr;
    winrtDevice_ = nullptr;
    device_ = nullptr;
}

void ScreenCapture::OnFrameArrived(Direct3D11CaptureFramePool const& pool, winrt::Windows::Foundation::IInspectable const&) {
    std::lock_guard lock(mutex_);
    if (!pool_) return;
    auto frame = pool.TryGetNextFrame();
    if (!frame) return;

    auto contentSize = frame.ContentSize();
    if (contentSize.Width != size_.Width || contentSize.Height != size_.Height) {
        // Cambió la resolución del monitor: el siguiente fotograma ya llega con el tamaño nuevo
        size_ = contentSize;
        pool_.Recreate(winrtDevice_, DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size_);
        return;
    }

    int64_t captureUs = frame.SystemRelativeTime().count() / 10;
    if (minIntervalUs_ > 0 && lastFrameUs_ != 0 && captureUs - lastFrameUs_ < minIntervalUs_) return;
    lastFrameUs_ = captureUs;

    auto access = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
    winrt::com_ptr<ID3D11Texture2D> texture;
    if (FAILED(access->GetInterface(winrt::guid_of<ID3D11Texture2D>(), texture.put_void()))) return;
    if (onFrame_) onFrame_(texture.get(), captureUs);
}

}  // namespace senda
