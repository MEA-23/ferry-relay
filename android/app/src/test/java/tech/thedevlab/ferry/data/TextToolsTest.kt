package tech.thedevlab.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    private fun found(text: String, query: String) = TextTools.matches(text, query).map { text.substring(it.first, it.last + 1) }

    @Test fun ignoresCaseAndAccents() {
        assertEquals(listOf("Code"), found("Your Code is 123456", "code"))
        assertEquals(listOf("Café"), found("Meet at Café Nero", "cafe"))
        assertEquals("hello", TextTools.normalize("HÉLLO"))
    }

    @Test fun ignoresArabicHarakatAndLetterVariants() {
        assertEquals("اهلا", TextTools.normalize("أَهْلاً"))
        assertEquals(listOf("مُحَمَّد"), found("مرحبا مُحَمَّد", "محمد"))
        assertEquals(listOf("مدرسة"), found("في المدرسة", "مدرسه"))
        assertEquals(listOf("إلى"), found("ذهبت إلى البنك", "الي"))
        assertEquals(listOf("البنك"), found("رسالة من البنك", "البنك"))
        assertEquals(listOf("كتـــاب"), found("هذا كتـــاب", "كتاب"))
    }

    @Test fun arabicDigitsMatchWesternDigits() {
        assertEquals(listOf("١٢٣٤"), found("رمزك ١٢٣٤٥٦", "1234"))
        assertEquals(listOf("۹۸۷"), found("کد ۹۸۷", "987"))
    }

    @Test fun findsEveryOccurrenceAndKeepsIndicesAcrossEmoji() {
        assertEquals(listOf("there", "there"), found("hi 😀 there, there", "there"))
        assertEquals(listOf(6..10), TextTools.matches("hi 😀 there", "there"))
    }

    @Test fun blankQueryMatchesNothing() {
        assertTrue(TextTools.matches("anything", "  ").isEmpty())
        assertTrue(TextTools.matches("anything", "َ").isEmpty())
    }

    @Test fun directionFollowsFirstStrongCharacter() {
        assertTrue(TextTools.isRtl("مرحبا Hello"))
        assertTrue(TextTools.isRtl("123: مرحبا"))
        assertTrue(TextTools.isRtl("שלום"))
        assertFalse(TextTools.isRtl("Hello مرحبا"))
        assertFalse(TextTools.isRtl("123456"))
        assertFalse(TextTools.isRtl(""))
    }
}
