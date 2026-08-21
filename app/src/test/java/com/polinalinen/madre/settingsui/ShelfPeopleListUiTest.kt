package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.shelf.ShelfMember
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class ShelfPeopleListUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `shows all members and marks founder`() {
        val members = listOf(
            ShelfMember(userId = "u1", displayName = "Аня", isMe = true),
            ShelfMember(userId = "u2", displayName = "Петя", isMe = false),
        )
        rule.setContent {
            MadreTheme {
                ShelfPeopleList(
                    members = members,
                    familyOwnerId = "u2",
                )
            }
        }

        rule.onNodeWithText("Книги на полке").assertExists()
        rule.onNodeWithText("Аня").assertExists()
        rule.onNodeWithText("Петя").assertExists()
        rule.onNodeWithText("кто завёл полку").assertExists()
    }
}
