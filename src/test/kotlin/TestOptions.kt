import com.google.common.collect.ImmutableMap
import com.google.common.hash.Hashing
import com.sksamuel.hoplite.ConfigLoader
import io.appium.java_client.AppiumDriver
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.ios.IOSDriver
import io.ktor.util.*
import org.openqa.selenium.MutableCapabilities
import java.net.URL
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.logging.Logger
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.notExists

data class AppInfo(val name: String, val activity: String? = null, val path: String) {
    private val pathIsUrl = path.contains(':')

    val file: Path by lazy {
        if (pathIsUrl) {
            // If it's a remote file (URL) - use a local cache to avoid repeated downloads.
            val urlHash = Hashing.crc32().hashString(path, Charset.defaultCharset()).toString()
            val cacheFile = Path.of(".cache/$urlHash-" + Path.of(path).name)
            if (cacheFile.notExists()) {
                cacheFile.parent.createDirectories()
                URL(path).openStream().use { Files.copy(it, cacheFile) }
            }
            cacheFile.toRealPath()
        } else {
            var appPath = Path.of(path)
            if (!appPath.isAbsolute) {
                appPath = TestOptions.configFile.parent.resolve(appPath)
            }
            appPath.toRealPath()
        }
    }
}

data class TestOptions(
    val apps: List<AppInfo>,
    val startupTimeTest: StartupTimeTest.Options?,
    val binarySizeTest: BinarySizeTest.Options?,
    private val server: Server = if (isCI) Server.SauceLabs else Server.LocalHost,
) {
    val logger: Logger = Logger.getLogger("AppiumTest")

    val platform: Platform = when (Path.of(apps.first().path).extension.toLowerCasePreservingASCIIRules()) {
        "apk" -> Platform.Android
        "aab" -> Platform.Android
        "app" -> Platform.IOS
        "ipa" -> Platform.IOS
        else -> throw Exception("Unknown app extension - cannot determine platform")
    }

    companion object {
        val isCI = System.getenv().containsKey("CI")
        val configFile = Path.of(System.getenv()["TEST_CONFIG"] ?: "./tests/android.yml")
        val instance: TestOptions = ConfigLoader().loadConfigOrThrow(configFile.toString())
    }

    enum class Platform {
        Android,
        IOS
    }

    enum class Server {
        LocalHost,
        SauceLabs
    }

    fun createDriver(): AppiumDriver {
        val caps = capabilities()

        when (server) {
            Server.LocalHost -> {
                val otherAppsPaths = apps.map {
                    logger.info("Adding app ${it.name} from ${it.path} to 'appium:otherApps'")
                    it.file.toString().replace('\\', '/')
                }

                // Local Appium requires JSON array (i.e. a string) instead of a list.
                caps.setCapability(
                    "appium:otherApps",
                    "[\"${otherAppsPaths.joinToString("\", \"")}\"]"
                )
            }

            Server.SauceLabs -> {
                val otherAppsPaths = apps.map {
                    val fileId = SauceLabs.uploadApp(it)
                    val result = "storage:$fileId"
                    logger.info("Adding app ${it.name} from ${it.path} to 'appium:otherApps' as '$result")
                    result
                }

                // SauceLabs requires this to be a list.
                caps.setCapability("appium:otherApps", otherAppsPaths)
            }
        }

        logger.info("Launching Appium $platform driver with the following options: ${caps.asMap()}")

        return when (platform) {
            Platform.Android -> AndroidDriver(url, caps)
            Platform.IOS -> IOSDriver(url, caps)
        }
    }

    private val url: URL = when (server) {
        Server.LocalHost -> URL("http://127.0.0.1:4723")
        Server.SauceLabs -> URL("https://${SauceLabs.user}:${SauceLabs.key}@ondemand.${SauceLabs.region}.saucelabs.com:443/wd/hub")
    }

    private fun capabilities(): MutableCapabilities {
        val caps = MutableCapabilities()
        caps.setCapability("appium:disableWindowAnimation", true)

        when (server) {
            Server.LocalHost -> {}
            Server.SauceLabs -> {
                val env = System.getenv()
                val sauceOptions = MutableCapabilities()
                // Appium v2 required for Android logcat access.
                sauceOptions.setCapability("appiumVersion", "2.0.0")
                sauceOptions.setCapability("name", "Performance tests")
                sauceOptions.setCapability(
                    "build",
                    if (isCI) "CI ${env["GITHUB_REPOSITORY"]} ${env["GITHUB_REF"]} ${env["GITHUB_RUN_ID"]}"
                    else "Local build ${LocalDateTime.now()}"
                )
                sauceOptions.setCapability(
                    "tags",
                    listOf(
                        platform.toString().toLowerCasePreservingASCIIRules(),
                        if (isCI) "ci" else "local"
                    )
                )
                caps.setCapability("sauce:options", sauceOptions)

                // See https://github.com/appium/java-client/issues/1242#issuecomment-539075905
                //   UnsupportedCommandException: unknown command: Cannot call non W3C standard command while in W3C mode
                //   Command: [d3d1a56d-a224-4e05-b99e-a424bb77230d, getLog {type=logcat}]
                caps.setCapability("appium:chromeOptions", ImmutableMap.of("w3c", false))
            }
        }

        when (platform) {
            Platform.Android -> {
                caps.setCapability("platformName", "Android")
                caps.setCapability("appium:automationName", "UiAutomator2")

                if (server == Server.SauceLabs) {
                    // Pixel 4 XL - ARM | octa core | 1785 MHz
                    caps.setCapability("appium:deviceName", "Google Pixel 4 XL")
                    // Pixel 4 XL has three devices, one for each Android 10, 11, 12.
                    caps.setCapability("appium:platformVersion", "12")
                }
            }

            Platform.IOS -> {
                caps.setCapability("platformName", "iOS")
                caps.setCapability("appium:automationName", "XCUITest")

                if (server == Server.SauceLabs) {
                    // iPhone 12 Pro & iPhone 12 Pro Max | hexa core | 1900 MHz
                    caps.setCapability("appium:deviceName", "iPhone 12 Pro.*")
                    caps.setCapability("appium:platformVersion", "14.8")
                } else {
                    // Locally, we use any simulator, because real-device setup is a pain:
                    // https://appium.io/docs/en/drivers/ios-xcuitest-real-devices/
                }
            }
        }

        return caps
    }
}
