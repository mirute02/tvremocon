package com.tvremocon.widget

import com.tvremocon.ir.IrKey

/**
 * A button's meaning, independent of what any particular remote calls it.
 *
 * [label] is what gets printed on the button, and follows the wording on a Japanese TV
 * remote rather than the protocol name — the hub calls it "HOMEPAGE", the physical remote
 * says ホーム, and the widget should read like the thing it replaces.
 */
enum class RemoteFunction(val label: String, private vararg val candidates: String) {
    POWER("電源", "POWER"),
    INPUT("入力切換", "INPUT", "INPUT 1", "TV/DTV"),
    MUTE("消音", "MUTE"),
    SCREEN_INFO("画面表示", "INFO", "PROGRAM INFORMATION"),

    TERRESTRIAL("地デジ", "Terrestrial Digital", "Digital Terrest", "TV/DTV"),
    SATELLITE_BS("BS", "BS", "Broadcast Sat"),
    SATELLITE_CS("CS", "CS"),

    DIGIT_1("1", "1"),
    DIGIT_2("2", "2"),
    DIGIT_3("3", "3"),
    DIGIT_4("4", "4"),
    DIGIT_5("5", "5"),
    DIGIT_6("6", "6"),
    DIGIT_7("7", "7"),
    DIGIT_8("8", "8"),
    DIGIT_9("9", "9"),
    DIGIT_10("10", "10", "0"),
    DIGIT_11("11", "11", "Digit 11"),
    DIGIT_12("12", "12", "Digit 12"),

    VOLUME_UP("音量 ＋", "VOL+"),
    VOLUME_DOWN("音量 －", "VOL-"),
    CHANNEL_UP("CH ▲", "CH+"),
    CHANNEL_DOWN("CH ▼", "CH-"),

    GUIDE("番組表", "GUIDE", "PROGRAM LIST"),
    PROGRAM_INFO("番組説明", "PROGRAM INFORMATION", "INFO"),
    TIMESHIFT("タイムシフト", "Time Slip", "Instant Replay"),

    // The four coloured keys along the middle of every Japanese remote. Ordered blue, red,
    // green, yellow to match the hardware, which is not the order the protocol lists them in.
    COLOR_BLUE("青", "BLUE"),
    COLOR_RED("赤", "RED"),
    COLOR_GREEN("緑", "GREEN"),
    COLOR_YELLOW("黄", "YELLOW"),

    UP("▲", "NAVIGATE_UP"),
    DOWN("▼", "NAVIGATE_DOWN"),
    LEFT("◀", "NAVIGATE_LEFT"),
    RIGHT("▶", "NAVIGATE_RIGHT"),
    OK("決定", "OK"),

    HOME("ホーム", "HOMEPAGE", "HOME"),
    MENU("メニュー", "MENU"),
    SUBMENU("サブメニュー", "POPMENU", "MENU"),
    BACK("戻る", "BACK", "LAST"),
    EXIT("終了", "EXIT"),

    SUBTITLE("字幕", "Subtitles"),
    DATA("dデータ", "D-Data", "DATA"),
    SETTINGS("設定", "Setup Menu", "MENU"),

    HDMI_1("HDMI1", "HDMI1", "INPUT 1"),
    HDMI_2("HDMI2", "HDMI2", "INPUT 2"),
    HDMI_3("HDMI3", "HDMI3", "INPUT 3");

    /**
     * The first candidate this remote actually has.
     *
     * Matching is by exact protocol name, in the order listed — `display_name` is truncated
     * to four bytes for downloaded keys, so there is nothing else reliable to match on. Case
     * is ignored because remote databases are not consistent about it.
     */
    fun resolve(keys: List<IrKey>): IrKey? {
        val byName = keys.associateBy { it.name.lowercase() }
        return candidates.firstNotNullOfOrNull { byName[it.lowercase()] }
    }
}
