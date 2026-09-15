// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sirga Studio contributors
#pragma once

#include "Common.h"

#include <vector>

namespace sirga {

enum class DeviceKind : uint8_t { Camera = 1, Microphone = 2 };

/** Cámara o micrófono del PC que el móvil puede usar como fuente. [id] es estable mientras siga conectado. */
struct PcDevice {
    DeviceKind kind = DeviceKind::Camera;
    std::wstring id;
    std::wstring name;

    bool operator==(const PcDevice&) const = default;
};

/** Cámaras (Media Foundation) y micrófonos (WASAPI) conectados ahora mismo. Necesita COM iniciado en el hilo. */
std::vector<PcDevice> EnumerateDevices();

}  // namespace sirga
