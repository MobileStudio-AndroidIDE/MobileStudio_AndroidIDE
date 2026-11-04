package com.aide.mobilestudio

import androidx.core.content.FileProvider

/**
 * App-specific FileProvider.
 *
 * The manifest merger deduplicates <provider> elements by their android:name, so the
 * app declares this subclass instead of the bare androidx class: it lets the app's
 * own provider (authority "${packageName}.provider") coexist with the one declared
 * by :godot-lib, whose GodotIO code resolves the authority "${packageName}.fileprovider".
 */
class AppFileProvider : FileProvider()
