package com.polinalinen.madre.navigation

import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=MadreApplication::class)
class BookBookmarkDetailsTest {
    @Test fun optionalRealSectionSurvivesColdReadWithoutChangingLegacyPositionEncoding() {
        val app=ApplicationProvider.getApplicationContext<MadreApplication>()
        val namespace="synthetic-bookmark-${UUID.randomUUID()}"
        val prefs=app.getSharedPreferences(namespace,0)
        prefs.edit().putString("bookmark","home_bread:2").commit()
        BookPreferences(app,namespace).use{assertEquals(BookBookmark("home_bread",2),it.bookmark)
            it.saveBookmark(BookBookmark("home_bread",2,"Как печём",3))}
        assertEquals("home_bread:2",prefs.getString("bookmark",null))
        assertEquals(BookBookmark("home_bread",2),BookBookmark.decode(prefs.getString("bookmark",null)))
        BookPreferences(app,namespace).use{assertEquals(BookBookmark("home_bread",2,"Как печём",3),it.bookmark)
            it.saveBookmark(BookBookmark("ciabatta",1))}
        BookPreferences(app,namespace).use{assertEquals(BookBookmark("ciabatta",1),it.bookmark)}
    }
}
