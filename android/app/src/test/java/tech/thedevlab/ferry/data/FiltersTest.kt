package tech.thedevlab.ferry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as ios/FerryKitTests/FerryKitTests.swift's FiltersTests, so both platforms score alike. */
class FiltersTest {
    @Test fun tokensAreNormalizedWordsAndPairs() {
        assertEquals(setOf("big", "sale", "big sale"), Filters.tokens("BIG sale!"))
        // Numbers alone and single letters are dropped; words keep their order for pairs.
        assertEquals(setOf("50off", "now", "50off now"), Filters.tokens("50off 50 a now"))
        assertEquals(setOf("عرض", "خاص", "عرض خاص"), Filters.tokens("عَرْض خاص"))
    }

    @Test fun targets() {
        assertEquals(Filters.Target(Filters.SENDER, "+2010"), Filters.target("p:dev1:+2010"))
        assertEquals(Filters.Target(Filters.APP, "com.whatsapp"), Filters.target("n:dev1:com.whatsapp"))
        assertEquals(null, Filters.target("x:dev1"))
    }

    @Test fun signals() {
        assertEquals(4.0 + 1, Filters.signals("amazon", "Deals today. Reply STOP to opt out"), 0.0)
        assertEquals(1.0 + 1 + 1, Filters.signals("12345", "50% off at https://shop.example"), 0.0)
        assertEquals(4.0, Filters.signals("+201001234567", "لإلغاء الاشتراك أرسل ٠"), 0.0)
        assertEquals(0.0, Filters.signals("+201001234567", "See you at 5?"), 0.0)
    }

    @Test fun codes() {
        assertTrue(Filters.looksLikeCode("Your verification code is 482913"))
        assertTrue(Filters.looksLikeCode("رمز التحقق الخاص بك ٤٨٢٩"))
        assertFalse(Filters.looksLikeCode("Code word: banana"))
        assertFalse(Filters.looksLikeCode("Order 123456 shipped"))
        assertFalse(Filters.looksLikeCode("Shopping week: 1000 deals"))
    }

    @Test fun learnedNeedsExamplesOfBoth() {
        val counts = mapOf("sale" to (3 to 0), "hi" to (0 to 5))
        assertEquals(0.0, Filters.learned(setOf("sale"), Filters.Model(1, 5, counts)), 0.0)
        assertEquals(0.0, Filters.learned(setOf("sale"), Filters.Model(3, 1, counts)), 0.0)
    }

    @Test fun learnedScores() {
        val model = Filters.Model(3, 5, mapOf("sale" to (3 to 0), "hi" to (0 to 5), "today" to (2 to 2), "rare" to (1 to 0)))
        // ln(4/5) - ln(1/7) = 1.7228, capped at 1.5; ln(1/5) - ln(6/7) = -1.4553; unseen words count for nothing.
        assertEquals(1.5, Filters.learned(setOf("sale"), model), 1e-9)
        assertEquals(-1.4553, Filters.learned(setOf("hi"), model), 1e-4)
        assertEquals(1.5 - 1.4553 + 0.3365, Filters.learned(setOf("sale", "hi", "today", "zebra"), model), 1e-3)
        // Used by only one muted sender: not evidence of marketing.
        assertEquals(0.0, Filters.learned(setOf("rare"), model), 0.0)
    }

    @Test fun nameLinkAndPercentAloneDontSuggest() {
        assertTrue(Filters.score("bank", "Pay 10% less at https://bank.example", Filters.Model.EMPTY) < Filters.SUGGEST)
        assertTrue(Filters.score("+201001234567", "Reply STOP to opt out", Filters.Model.EMPTY) >= Filters.SUGGEST)
    }

    @Test fun learnedIsClamped() {
        val counts = (1..20).associate { "w$it" to (10 to 0) }
        assertEquals(6.0, Filters.learned(counts.keys, Filters.Model(10, 10, counts)), 0.0)
    }

    @Test fun words() {
        assertTrue(Filters.matchesWord("BIG Sale today", listOf(TextTools.normalize("sale"))))
        assertTrue(Filters.matchesWord("خصم خاص", listOf(TextTools.normalize("خَصم"))))
        assertFalse(Filters.matchesWord("hello", emptyList()))
    }

    @Test fun suggestions() {
        val model = Filters.Model(3, 5, mapOf("sale" to (3 to 0), "big sale" to (2 to 0), "hi" to (2 to 1), "go" to (3 to 0), "off" to (1 to 0)))
        assertEquals(listOf("sale", "big sale"), Filters.suggestions(model, emptySet()))
        assertEquals(listOf("big sale"), Filters.suggestions(model, setOf("sale")))
    }
}
