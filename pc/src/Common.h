// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Nexo Live contributors
#pragma once

#include <unknwn.h>
#include <windows.h>

#include <winrt/base.h>

#include <cstdint>
#include <string>

namespace nexo {

/** Microsegundos del reloj de alto rendimiento: la misma base que usa Windows.Graphics.Capture. */
inline int64_t NowUs() {
    static const int64_t frequency = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return counter.QuadPart / frequency * 1'000'000 + counter.QuadPart % frequency * 1'000'000 / frequency;
}

std::string ToUtf8(const std::wstring& text);
std::wstring FromUtf8(const std::string& text);

/** Registro de diagnóstico en %LOCALAPPDATA%\NexoLivePC\registro.txt y en el depurador. */
void Log(const wchar_t* format, ...);

}  // namespace nexo
