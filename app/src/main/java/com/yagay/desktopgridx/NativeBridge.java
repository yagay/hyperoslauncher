package com.yagay.desktopgridx;

final class NativeBridge {
    private NativeBridge() {}
    static native String analyzeElf(String path);
}
