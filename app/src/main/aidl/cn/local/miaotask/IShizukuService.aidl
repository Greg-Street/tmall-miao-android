package cn.local.miaotask;

import android.os.ParcelFileDescriptor;

interface IShizukuService {
    int protocolVersion() = 0;
    void setActive(boolean active) = 1;
    String foreground() = 2;
    ParcelFileDescriptor capture() = 3;
    void gesture(in float[] xs, in float[] ys, long duration, String expectedPackage) = 4;
    void back(String expectedPackage) = 5;
    void inputText(String text, String expectedPackage) = 6;
    void launchTaobao(String component, int userId) = 7;
    int executionUid() = 8;
    void submitSearch(String expectedPackage) = 9;
    void openCoinPage(int userId) = 10;
    void beginChineseInput(String component, int userId) = 11;
    void endChineseInput() = 12;
    void destroy() = 16777114;
}
