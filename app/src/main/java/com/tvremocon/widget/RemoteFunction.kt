package com.tvremocon.widget

import com.tvremocon.ir.IrKey

/**
 * A button's meaning, independent of what any particular remote calls it.
 *
 * Slots are assigned a [RemoteFunction] by default and resolved to a concrete key name per
 * remote. The user can override any slot later; this only decides where things start.
 */
enum class RemoteFunction(val label: String, private vararg val candidates: String) {
    POWER("電源", "POWER"),
    INPUT("入力", "INPUT", "INPUT 1", "TV/DTV"),
    MUTE("消音", "MUTE"),
    HOME("ホーム", "HOMEPAGE", "HOME"),
    MENU("メニュー", "MENU", "POPMENU", "Setup Menu"),
    BACK("戻る", "BACK", "LAST"),
    EXIT("終了", "EXIT"),
    INFO("画面表示", "INFO", "PROGRAM INFORMATION"),
    GUIDE("番組表", "GUIDE", "PROGRAM LIST"),

    CHANNEL_UP("CH ＋", "CH+"),
    CHANNEL_DOWN("CH －", "CH-"),
    VOLUME_UP("音量 ＋", "VOL+"),
    VOLUME_DOWN("音量 －", "VOL-"),

    UP("▲", "NAVIGATE_UP"),
    DOWN("▼", "NAVIGATE_DOWN"),
    LEFT("◀", "NAVIGATE_LEFT"),
    RIGHT("▶", "NAVIGATE_RIGHT"),
    OK("決定", "OK"),

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

    HDMI_1("HDMI1", "HDMI1", "INPUT 1"),
    HDMI_2("HDMI2", "HDMI2", "INPUT 2"),
    HDMI_3("HDMI3", "HDMI3", "INPUT 3"),

    TERRESTRIAL("地デジ", "Terrestrial Digital", "Digital Terrest", "TV/DTV"),
    SATELLITE_BS("BS", "BS", "Broadcast Sat"),
    SATELLITE_CS("CS", "CS");

    /**
     * The first candidate this remote actually has.
     *
     * Matching is by exact protocol name, in the order listed — `display_name` is unusable
     * for downloaded keys, so there is nothing else reliable to match on. Case is ignored
     * because remote databases are not consistent about it.
     */
    fun resolve(keys: List<IrKey>): IrKey? {
        val byName = keys.associateBy { it.name.lowercase() }
        return candidates.firstNotNullOfOrNull { byName[it.lowercase()] }
    }
}
