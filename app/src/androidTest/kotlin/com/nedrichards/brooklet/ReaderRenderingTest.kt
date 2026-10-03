package com.nedrichards.brooklet

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import com.nedrichards.brooklet.designsystem.BrookletTheme
import com.nedrichards.brooklet.model.DocumentBlock
import com.nedrichards.brooklet.model.HtmlDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inlineFormattingSurvivesIntoComposeSpans() {
        val rendered = themeSafeArticleText("x<sup>2</sup> H<sub>2</sub>O <del>old</del> <s>removed</s>")
        assertEquals("x2 H2O old removed", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript })
        assertTrue(rendered.spanStyles.any { it.item.baselineShift == BaselineShift.Subscript })
        assertEquals(2, rendered.spanStyles.count { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test fun mediaUsesSafeResolvedExternalLinks() {
        val paragraphs = HtmlDocumentParser.parse("<iframe src='/embed'></iframe><audio><source src='/audio.mp3'></audio><video src='javascript:bad'></video>")
            .filterIsInstance<DocumentBlock.Paragraph>()
        val rendered = paragraphs.map { themeSafeArticleText(it.html!!, "https://example.com/article") }
        assertEquals("https://example.com/embed", (rendered[0].getLinkAnnotations(0, rendered[0].length).single().item as LinkAnnotation.Url).url)
        assertEquals("https://example.com/audio.mp3", (rendered[1].getLinkAnnotations(0, rendered[1].length).single().item as LinkAnnotation.Url).url)
        assertTrue(rendered[2].getLinkAnnotations(0, rendered[2].length).isEmpty())
    }

    @Test fun mergedTableRendersAndWideColumnsCanBeReached() {
        val table = HtmlDocumentParser.parse("<table><tr><th colspan='2'>Merged heading</th><th>Last column</th></tr><tr><td rowspan='2'>Tall cell</td><td>Middle</td><td>Far right</td></tr><tr><td colspan='2'><a href='/next'>Linked cell</a></td></tr></table>").single() as DocumentBlock.Table
        compose.setContent { BrookletTheme(dynamicColor = false) { Column { ArticleTable(table, "https://example.com/article") } } }
        compose.onNodeWithText("Merged heading").assertIsDisplayed()
        compose.onNodeWithText("Tall cell").assertIsDisplayed()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "reader-table.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Middle").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Far right").assertIsDisplayed()
        compose.onNodeWithText("Linked cell").assertIsDisplayed()
    }
}
