package com.polinalinen.madre.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 19: колофон разложен на пять разделов.
 *
 * До этого страница была одной лентой из четырнадцати строк, а внизу — эссе про
 * уведомления, спорившее со строкой «Напоминания: вкл» семью экранами выше.
 * Проверяется здесь не красота, а то, что можно проверить: что разделы названы,
 * что убранное убрано, что оба варианта оформления видны сразу, и что форма
 * входа в общую книгу не разворачивается сама.
 *
 * Плюс правило языка кнопок (hard rule №9) — на этой странице оно вступает в
 * силу вместе с этой правкой: всякое нажатие называет себя кнопкой, говорит,
 * что сделает, и не меньше пальца ростом.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class SettingsIaUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun showSettings(
        intervalHours: Int = 24,
        calmMode: Boolean = true,
        bakeCount: Int = 3,
        feedingCount: Int = 7,
    ) {
        rule.setContent {
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    bakeCount = bakeCount,
                    feedingCount = feedingCount,
                    intervalHours = intervalHours,
                    calmMode = calmMode,
                )
            }
        }
        rule.waitForIdle()
    }

    /**
     * Надзаголовки книга печатает прописными (PageLabel), поэтому и ищем их
     * прописными: тест смотрит на страницу, а не на исходник.
     */
    @Test
    fun `the colophon is five named sections, not one long strip`() {
        showSettings()
        assertThat(Sections.ALL).hasSize(5)
        Sections.ALL.forEach { title ->
            rule.onNodeWithText(title.uppercase()).assertExists("нет раздела «$title»")
        }
    }

    @Test
    fun `the page introduces itself as the settings, and says what they are`() {
        showSettings()
        rule.onNodeWithText("Настройки").assertExists()
        rule.onNodeWithText("выходные данные книги").assertExists()
    }

    /**
     * «Тираж: одна семья» не отвечал ни на один вопрос, который человек задаёт
     * настройкам. Строка убрана, а не спрятана — искать её нечем.
     */
    @Test
    fun `the circulation line is gone for good`() {
        showSettings()
        rule.onNodeWithText("Тираж").assertDoesNotExist()
    }

    /** Ритм кормления остаётся одной accessible-кнопкой с merged label. */
    @Test
    fun `the feeding rhythm is exposed as one accessible control`() {
        showSettings(intervalHours = 24)
        val label = "Как часто кормить: Ваш ритм: раз в сутки"
        val node = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        assertThat(node.config.getOrNull(SemanticsActions.OnClick)?.label).isEqualTo(label)
        rule.onNodeWithText("раз в 24 часа", useUnmergedTree = true).assertDoesNotExist()
    }

    /**
     * Оба оформления названы и стоят рядом: узнать, что бывает кроме
     * «спокойного», больше не нужно нажатием вслепую.
     */
    @Test
    fun `both looks are named at once, so neither has to be discovered by tapping`() {
        showSettings(calmMode = true)
        rule.onNodeWithText("спокойное").assertExists()
        rule.onNodeWithText("живое").assertExists()
    }

    @Test
    fun `the journal counts bakes and feedings in one line`() {
        showSettings(bakeCount = 3, feedingCount = 7)
        rule.onNodeWithText("В журнале").assertExists()
        rule.onNodeWithText("3 выпечек · 7 кормлений").assertExists()
    }

    /**
     * Форма входа в общую книгу занимала треть колофона у всех, включая тех,
     * кто общей книгой не пользуется. Теперь это строка и кнопка, а почта с
     * паролем появляются, когда о них попросили.
     *
     * Секция берётся отдельно, а не со всей страницы: на странице её состояние
     * приезжает из FamilyBookViewModel, который в этот момент ещё спрашивает
     * сеть, и тест мерил бы не свёрнутость формы, а скорость ответа.
     */
    @Test
    fun `the family form stays folded until it is asked for`() {
        rule.setContent {
            MadreTheme {
                FamilyBookSection(
                    state = FamilyBookState.SignedOut,
                    onSignIn = { _, _ -> },
                    onRegister = { _, _, _ -> },
                    onCreateFamily = {},
                    onJoinFamily = {},
                    onRotateInvite = {},
                    onSignOut = {},
                    onCodeHandled = {},
                )
            }
        }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Подключить семью…").assertExists()

        rule.onNodeWithText("Подключить семью…").performClick()
        rule.waitForIdle()

        // Почта, пароль и подпись — три поля, и ни одного до просьбы.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        rule.onNodeWithText("Почта").assertExists()
    }

    // ————— hard rule №9: язык кнопок на этой странице —————

    /**
     * Поля ввода из проверки исключены намеренно, а не ради зелёного прогона:
     * поле для имени — не кнопка, роль Role.Button на нём была бы неправдой для
     * TalkBack. Всё остальное нажимаемое — кнопка и обязано себя так называть.
     */
    private fun tappableNodes(): List<SemanticsNode> =
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filterNot { it.config.getOrNull(SemanticsActions.SetText) != null }
            .filterNot { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    private fun describe(node: SemanticsNode): String {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return listOfNotNull(text, description).firstOrNull { it.isNotBlank() } ?: "узел #${node.id}"
    }

    @Test
    fun `everything tappable in the colophon says it is a button`() {
        showSettings()
        val nodes = tappableNodes()
        assertThat(nodes).isNotEmpty()
        nodes.forEach { node ->
            assertWithMessage("${describe(node)}: роль")
                .that(node.config.getOrNull(SemanticsProperties.Role))
                .isEqualTo(Role.Button)
        }
    }

    @Test
    fun `everything tappable in the colophon says what it will do`() {
        showSettings()
        tappableNodes().forEach { node ->
            val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
            assertWithMessage("${describe(node)}: подпись действия").that(label).isNotNull()
            assertWithMessage("${describe(node)}: подпись действия").that(label!!.isBlank()).isFalse()
        }
    }

    /** Мишень — те же 48dp, что у BookButton: правило книги одно на все нажатия. */
    @Test
    fun `nothing tappable in the colophon is smaller than a finger`() {
        showSettings()
        val density = rule.density.density
        tappableNodes().forEach { node ->
            // Размер раскладки, а не boundsInRoot: последний обрезан окном
            // прокрутки, и строка, уехавшая за нижний край, «не проходила» бы
            // по мишени, будучи ростом с палец.
            val heightDp = node.size.height / density
            assertWithMessage("${describe(node)}: высота мишени, dp").that(heightDp).isAtLeast(48f)
        }
    }
}
