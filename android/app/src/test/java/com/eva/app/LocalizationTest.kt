// android/app/src/test/java/com/eva/app/LocalizationTest.kt
package com.eva.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cevirilerin sessizce bozulmasini engelleyen testler.
 *
 * NEDEN GEREKLI
 * -------------
 * İki hata türü de derleme sırasında YAKALANMAZ ve ancak kullanıcı
 * yanlış dilde bir ekran gördüğünde fark edilir:
 *
 *  1. Bir metnin çeviri dosyası yerine doğrudan koda yazılması —
 *     İskoçya'daki kullanıcı o ekranı Türkçe görür.
 *  2. Bir dilde anahtarın eksik kalması — o dilde uygulama varsayılana
 *     düşer, biçimlendirilmiş dizelerde ise ÇÖKER.
 */
class LocalizationTest {

    private val projectRoot: File = File("src/main").absoluteFile
    private val resDir = File(projectRoot, "res")
    private val sourceDir = File(projectRoot, "java")

    private val locales = listOf("values", "values-tr", "values-de", "values-es", "values-fr")

    /** Türkçeye özgü harfler; kodda gömülü metni bulmak için. */
    private val turkishChars = "çğıöşüÇĞİÖŞÜ".toSet()

    /**
     * Kullanıcıya görünen metin kalıpları.
     *
     * Log ve yorum satırları kapsam DIŞI: onlar geliştirici içindir ve
     * çevrilmeleri gerekmez.
     */
    private val uiTextPatterns = listOf(
        Regex("""\bText\(\s*"([^"]+)""""),
        Regex("""contentDescription\s*=\s*"([^"]+)""""),
        Regex("""\bplaceholder\s*=\s*\{\s*Text\("([^"]+)""""),
        Regex("""\blabel\s*=\s*"([^"]+)""""),
    )

    @Test
    fun `kullaniciya gorunen metinler koda gomulu olmamali`() {
        val offenders = mutableListOf<String>()

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

                    uiTextPatterns.forEach { pattern ->
                        pattern.findAll(line).forEach { match ->
                            val text = match.groupValues[1]
                            if (text.any { it in turkishChars }) {
                                offenders += "${file.name}:${index + 1} → \"$text\""
                            }
                        }
                    }
                }
            }

        assertTrue(
            "Bu metinler çeviri dosyalarını atlayıp koda yazılmış. " +
                "strings.xml'e taşıyıp stringResource() ile okuyun:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `tum diller ayni anahtarlari icermeli`() {
        val keysByLocale = locales.associateWith { locale ->
            val file = File(resDir, "$locale/strings.xml")
            assertTrue("Eksik dosya: $locale/strings.xml", file.exists())
            Regex("""<string name="([^"]+)"""")
                .findAll(file.readText())
                .map { it.groupValues[1] }
                .toSet()
        }

        val reference = keysByLocale.getValue("values")

        keysByLocale.forEach { (locale, keys) ->
            val missing = reference - keys
            assertTrue(
                "$locale dosyasında eksik anahtarlar var. Bu diller varsayılana " +
                    "düşer; biçimlendirilmiş dizelerde ise uygulama ÇÖKER:\n" +
                    missing.sorted().joinToString("\n"),
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun `bicimlendirme yer tutuculari tum dillerde ayni olmali`() {
        // "%1$s" gibi yer tutucular dilden dile DEĞİŞMEZ. Bir çeviride
        // eksikse ya da fazlaysa uygulama o dilde çöker.
        val placeholder = Regex("""%\d+\$[sd]""")

        val stringsByLocale = locales.associateWith { locale ->
            Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(File(resDir, "$locale/strings.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        }

        val reference = stringsByLocale.getValue("values")

        stringsByLocale.forEach { (locale, strings) ->
            reference.forEach { (key, referenceValue) ->
                val translated = strings[key] ?: return@forEach
                assertEquals(
                    "$locale → $key: yer tutucular eşleşmiyor",
                    placeholder.findAll(referenceValue).map { it.value }.toSet(),
                    placeholder.findAll(translated).map { it.value }.toSet(),
                )
            }
        }
    }
}
