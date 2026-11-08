package dev.ryuya.appbatterytools

/** New targets are explicitly reviewed; no global location or alarm interception. */
object Targets {
    const val DIGIPOLICE = "jp.co.dawncorp.DigiPolice"
    const val CERTIFICATE = "70c0b904316960cb6797e20dc94ea7d96e39a0606b653f10c9367696e161fbd7"
    const val RULE = "digipolice-service-location-cleanup-v2"
    const val AREA_PREFERENCES = "titanium"
    const val AREA_KEY = "IS_ARROWED_AREA_NOTIFICATION"
    const val AUTHORITY = "dev.ryuya.appbatterytools.module"

    data class Option(val key: String, val label: Int)

    data class Target(
        val packageName: String,
        val name: String,
        val certificate: String,
        val rule: String,
        val detail: Int,
        val options: List<Option>,
    )

    const val WINDOWS = "com.microsoft.appmanager"
    const val GOVEE = "com.govee.home"
    const val SESAME = "co.candyhouse.sesame2"
    const val EQ = "rakkashin.earpieceeq"
    const val ECOVACS = "com.eco.global.app"
    const val WEATHER = "wni.WeathernewsTouch.jp"
    const val SMARTTHINGS = "com.samsung.android.oneconnect"
    const val TAPO = "com.tplink.iot"
    const val SIMEJI = "com.adamrocker.android.input.simeji"
    val all =
        listOf(
            Target(
                DIGIPOLICE,
                "Digi Police",
                CERTIFICATE,
                RULE,
                R.string.digi_detail,
                listOf(Option("digipolice", R.string.digi_fix)),
            ),
            Target(
                WINDOWS,
                "Link to Windows",
                "e843ee3da1195d6afb89caa3ce7427b08fc01fd878a24fa56e962c35c71d5e70",
                "windows-owned-resources-v1",
                R.string.windows_detail,
                listOf(
                    Option("windows_database", R.string.windows_database),
                    Option("windows_connections", R.string.windows_connections),
                ),
            ),
            Target(
                GOVEE,
                "Govee Home",
                "94257a44978717e568b00c24bfcc385ec2f1cfeaf42885bbeb3232c94907eb43",
                "framework-ble-wifi-v1",
                R.string.ble_wifi_detail,
                listOf(Option("govee_wifi", R.string.ble_wifi)),
            ),
            Target(
                SESAME,
                "Sesame",
                "f2ac719bf1457a88171aecbcb97b64c5a58eef3af8414cbd7d92afc649fd68ab",
                "framework-ble-wifi-v1",
                R.string.ble_wifi_detail,
                listOf(Option("sesame_wifi", R.string.ble_wifi)),
            ),
            Target(
                EQ,
                "Earpiece EQ",
                "ef1e2b8d62d9b055b931674f611b31af5e9cdab0465a16294729985f892be6d6",
                "eq-route-computation-v1",
                R.string.eq_detail,
                listOf(Option("eq_events", R.string.eq_events)),
            ),
            Target(
                ECOVACS,
                "ECOVACS HOME",
                "f0e8e70c959a6f949db626d464ade231caa6ff2ce652e5720ec4e41f6fef8be8",
                "ecovacs-matter-release-v1",
                R.string.ecovacs_detail,
                listOf(Option("ecovacs_release", R.string.ecovacs_release)),
            ),
            Target(
                WEATHER,
                "Weathernews",
                "6ccb0ee3a9839b5a7c2b0e1eb8a744b73c730a4695863407efda6f92dd1798d9",
                "weather-owned-timer-v1",
                R.string.weather_timer_detail,
                listOf(Option("weather_timer", R.string.weather_timer)),
            ),
            Target(
                SMARTTHINGS,
                "SmartThings",
                "34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42",
                "smartthings-mdns-handoff-v1",
                R.string.smartthings_release_detail,
                listOf(Option("smartthings_release", R.string.smartthings_release)),
            ),
            Target(
                TAPO,
                "Tapo",
                "38eff43efb59313414fb442cee16399d48452e3c4b016cc1f2d073e815e243d7",
                "tapo-failed-mdns-acquisition-v1",
                R.string.tapo_release_detail,
                listOf(Option("tapo_release", R.string.tapo_release)),
            ),
            Target(
                SIMEJI,
                "Simeji",
                "4e5a78c0450316e38afab3ecb6bac9932c095f2b34360c40129857b78657e24f",
                "simeji-sdk-anr-polling-v1",
                R.string.simeji_anr_detail,
                listOf(Option("simeji_anr", R.string.simeji_anr)),
            ),
        )
    val optionKeys
        get() = all.flatMap { it.options }.map { it.key }.toSet() + "enabled"

    fun find(packageName: String) = all.singleOrNull { it.packageName == packageName }
}
