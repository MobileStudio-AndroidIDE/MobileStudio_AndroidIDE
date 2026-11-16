package com.aide.mobilestudio.logcat

/**
 * Logcat Bookmarks - logcat bookmarks management.
 */
class LogcatBookmarks(
    private val context: android.content.Context
) {
    private val bookmarks = mutableListOf<String>()

    fun addBookmark(bookmark: String) {
        bookmarks.add(bookmark)
    }

    fun getBookmarks(): List<String> = bookmarks

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, bookmarks)
        listView.adapter = adapter
        return listView
    }
}
