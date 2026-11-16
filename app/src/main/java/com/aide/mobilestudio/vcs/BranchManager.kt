package com.aide.mobilestudio.vcs

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.ListView

class BranchManager(
    private val context: Context
) {
    private val branches = mutableListOf<String>()

    fun addBranch(branch: String) {
        branches.add(branch)
    }

    fun getBranches(): List<String> = branches

    fun createView(): android.widget.ListView {
        val listView = android.widget.ListView(context)
        val adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, branches)
        listView.adapter = adapter
        return listView
    }
}