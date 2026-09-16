package dev.syumai.butler

import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class SearchCardsTest {
    @Test fun parsesFullItem() {
        val result = SearchCards.parse(
            """{"spoken":"A capybara is a large rodent.","items":[
                {"title":"Capybara","description":"A large rodent.","url":"https://example.com/capybara",
                 "wikipedia_title":"Capybara","image_query":"capybara animal"}
            ]}"""
        )
        assertEquals("A capybara is a large rodent.", result.spoken)
        assertEquals(1, result.items.size)
        val item = result.items[0]
        assertEquals("Capybara", item.title)
        assertEquals("A large rodent.", item.description)
        assertEquals("https://example.com/capybara", item.url)
        assertEquals("Capybara", item.wikipediaTitle)
        assertEquals("capybara animal", item.imageQuery)
    }

    @Test fun toleratesMissingOptionalFields() {
        // wikipedia_title is null (the model has nothing to cite) and image_query is blank — both
        // schema-legal per search_cards' strict json_schema (nullable via type:["string","null"]).
        val result = SearchCards.parse(
            """{"spoken":"ok","items":[{"title":"T","description":"D","url":"https://x","wikipedia_title":null,"image_query":""}]}"""
        )
        assertEquals(1, result.items.size)
        assertNull(result.items[0].wikipediaTitle)
        assertEquals("T", result.items[0].imageQuery) // falls back to the title when blank
    }

    @Test fun dropsItemsMissingTitleOrUrl() {
        val result = SearchCards.parse(
            """{"spoken":"ok","items":[
                {"title":"","description":"D","url":"https://x","wikipedia_title":null,"image_query":"q"},
                {"title":"T","description":"D","url":"","wikipedia_title":null,"image_query":"q"},
                {"title":"Good","description":"D","url":"https://y","wikipedia_title":null,"image_query":"q"}
            ]}"""
        )
        assertEquals(1, result.items.size)
        assertEquals("Good", result.items[0].title)
    }

    @Test fun emptyItemsArray() {
        val result = SearchCards.parse("""{"spoken":"42.","items":[]}""")
        assertEquals("42.", result.spoken)
        assertTrue(result.items.isEmpty())
    }

    @Test fun missingItemsFieldYieldsEmptyList() {
        val result = SearchCards.parse("""{"spoken":"42."}""")
        assertTrue(result.items.isEmpty())
    }

    @Test fun capsAtMaxItems() {
        val items = (1..8).joinToString(",") { i -> """{"title":"T$i","description":"D","url":"https://x/$i","wikipedia_title":null,"image_query":"q"}""" }
        val result = SearchCards.parse("""{"spoken":"ok","items":[$items]}""")
        assertEquals(SearchCards.MAX_ITEMS, result.items.size)
        assertEquals("T1", result.items.first().title)
        assertEquals("T5", result.items.last().title)
    }

    @Test fun truncatesLongDescriptions() {
        val long = "x".repeat(500)
        val result = SearchCards.parse("""{"spoken":"ok","items":[{"title":"T","description":"$long","url":"https://x","wikipedia_title":null,"image_query":"q"}]}""")
        assertEquals(300, result.items[0].description.length)
    }

    @Test(expected = JSONException::class)
    fun throwsOnInvalidJson() {
        SearchCards.parse("not json at all")
    }

    @Test fun buildContentLinesUpIndices() {
        val items = listOf(
            SearchCards.ParsedItem("Capybara", "d1", "https://a", null, "q1"),
            SearchCards.ParsedItem("Mount Fuji", "d2", "https://b", "Mount Fuji", "q2"),
        )
        val content = SearchCards.buildContent(items)
        assertEquals(1, content.length())
        val part = content.getJSONObject(0)
        assertEquals("output_text", part.getString("type"))
        val text = part.getString("text")
        assertEquals("Capybara\nMount Fuji", text)
        val annotations = part.getJSONArray("annotations")
        assertEquals(2, annotations.length())
        val first = annotations.getJSONObject(0)
        assertEquals("url_citation", first.getString("type"))
        assertEquals("https://a", first.getString("url"))
        assertEquals(0, first.getInt("start_index"))
        assertEquals("Capybara".length, first.getInt("end_index"))
        assertEquals(text.substring(first.getInt("start_index"), first.getInt("end_index")), "Capybara")
        val second = annotations.getJSONObject(1)
        assertEquals(text.substring(second.getInt("start_index"), second.getInt("end_index")), "Mount Fuji")
    }

    @Test fun buildContentEmptyItems() {
        val content = SearchCards.buildContent(emptyList())
        assertEquals("", content.getJSONObject(0).getString("text"))
        assertEquals(0, content.getJSONObject(0).getJSONArray("annotations").length())
    }

    @Test fun highlightMatchesExactTitle() {
        assertTrue(SearchCards.highlightMatches("I found info about Capybara facts.", "Capybara"))
    }

    @Test fun highlightMatchesLongTitlePrefix() {
        val title = "Capybara Facts | National Geographic"
        assertTrue(SearchCards.highlightMatches("Let's talk about Capybara Fac", title))
    }

    @Test fun highlightNoMatch() {
        assertFalse(SearchCards.highlightMatches("Completely unrelated text here.", "Mount Fuji"))
    }

    @Test fun highlightBlankInputs() {
        assertFalse(SearchCards.highlightMatches("", "Capybara"))
        assertFalse(SearchCards.highlightMatches("some transcript", ""))
    }

    @Test fun wikipediaSummaryUrlEncodesTitle() {
        val url = SearchCards.wikipediaSummaryUrl("カピバラ")
        assertTrue(url.startsWith("https://ja.wikipedia.org/api/rest_v1/page/summary/"))
        assertFalse(url.contains(" "))
    }

    @Test fun wikipediaSummaryUrlReplacesSpacesWithUnderscore() {
        val url = SearchCards.wikipediaSummaryUrl("Mount Fuji", lang = "en")
        assertTrue(url.startsWith("https://en.wikipedia.org/api/rest_v1/page/summary/Mount_Fuji"))
    }

    @Test fun wikipediaSearchUrlEncodesQuery() {
        val url = SearchCards.wikipediaSearchUrl("Mount Fuji", lang = "en")
        assertTrue(url.startsWith("https://en.wikipedia.org/w/rest.php/v1/search/page?q=Mount+Fuji"))
        assertTrue(url.endsWith("&limit=1"))
    }
}
